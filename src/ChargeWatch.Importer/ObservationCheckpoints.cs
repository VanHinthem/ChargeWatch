using Npgsql;

// Executed inside the same transaction as the national catalogue import.
// Deliberately records only new source observations: repeated snapshots do not invent durations.
internal static class ObservationCheckpoints
{
    public static async Task Capture(NpgsqlConnection db, NpgsqlTransaction tx)
    {
        var sql = """
            DO $$
            BEGIN
              IF NOT EXISTS (SELECT 1 FROM schema_migration WHERE version = '003_statistics') THEN
                RAISE EXCEPTION 'Sprint 3 statistics migration 003_statistics is required before enabling checkpoint capture';
              END IF;
            END $$;

            CREATE TEMP TABLE stage_observation ON COMMIT DROP AS
            SELECT e.id AS evse_id, e.location_id,
                   CASE upper(btrim(s.payload->'data'->>'status'))
                     WHEN 'AVAILABLE' THEN 'available'
                     WHEN 'CHARGING' THEN 'occupied'
                     WHEN 'RESERVED' THEN 'occupied'
                     WHEN 'BLOCKED' THEN 'occupied'
                     WHEN 'INOPERATIVE' THEN 'fault'
                     WHEN 'OUTOFORDER' THEN 'fault'
                     ELSE 'unknown'
                   END AS status_class,
                   NULLIF(s.payload->'data'->>'last_updated','')::timestamptz AS source_updated_at
            FROM stage_evse s
            JOIN charging_location l ON l.country_code=s.payload->>'country'
              AND l.party_id=s.payload->>'party' AND l.source_id=s.payload->>'id'
            JOIN evse e ON e.location_id=l.id AND e.source_uid=s.payload->>'uid';

            -- Snapshot checkpoint freshness and stale records BEFORE writing EVSE checkpoints.
            CREATE TEMP TABLE stage_fresh_evse ON COMMIT DROP AS
            SELECT s.evse_id, s.location_id
            FROM stage_observation s
            LEFT JOIN evse_observation_checkpoint cp ON cp.evse_id=s.evse_id
            WHERE s.source_updated_at IS NOT NULL
              AND (cp.evse_id IS NULL OR cp.source_updated_at IS NULL
                   OR s.source_updated_at > cp.source_updated_at);

            CREATE TEMP TABLE stage_stale_location ON COMMIT DROP AS
            SELECT DISTINCT s.location_id
            FROM stage_observation s
            JOIN evse_observation_checkpoint cp ON cp.evse_id=s.evse_id
            WHERE s.source_updated_at < cp.source_updated_at;

            -- Accept only unchanged states with successive source timestamps <=15 min.
            -- Split at Dutch local midnight (also handles 23/25-hour DST days).
            CREATE TEMP TABLE stage_reliability_interval ON COMMIT DROP AS
            WITH valid AS (
              SELECT s.evse_id,s.status_class,cp.source_updated_at AS begins_at,
                     s.source_updated_at AS ends_at
              FROM stage_observation s
              JOIN evse_observation_checkpoint cp ON cp.evse_id=s.evse_id
              WHERE s.source_updated_at > cp.source_updated_at
                AND s.source_updated_at <= cp.source_updated_at + interval '15 minutes'
                AND s.status_class=cp.status_class
            ),
            days AS (
              SELECT v.evse_id,v.status_class,v.begins_at,v.ends_at,
                     d.local_day::date AS observation_date,
                     (d.local_day AT TIME ZONE 'Europe/Amsterdam') AS day_start,
                     ((d.local_day + interval '1 day') AT TIME ZONE 'Europe/Amsterdam') AS day_end
              FROM valid v
              CROSS JOIN LATERAL generate_series(
                date_trunc('day',v.begins_at AT TIME ZONE 'Europe/Amsterdam'),
                date_trunc('day',v.ends_at AT TIME ZONE 'Europe/Amsterdam'),
                interval '1 day') AS d(local_day)
            )
            SELECT evse_id,status_class,observation_date,
                   floor(extract(epoch FROM
                     least(ends_at,day_end)-greatest(begins_at,day_start)))::bigint AS seconds
            FROM days
            WHERE least(ends_at,day_end)>greatest(begins_at,day_start);

            INSERT INTO evse_daily_reliability
              (evse_id,observation_date,operating_seconds,fault_seconds,unknown_seconds)
            SELECT evse_id,observation_date,
                   sum(CASE WHEN status_class IN ('available','occupied') THEN seconds ELSE 0 END),
                   sum(CASE WHEN status_class='fault' THEN seconds ELSE 0 END),
                   sum(CASE WHEN status_class='unknown' THEN seconds ELSE 0 END)
            FROM stage_reliability_interval
            GROUP BY evse_id,observation_date
            ON CONFLICT (evse_id,observation_date) DO UPDATE SET
              operating_seconds=evse_daily_reliability.operating_seconds+excluded.operating_seconds,
              fault_seconds=evse_daily_reliability.fault_seconds+excluded.fault_seconds,
              unknown_seconds=evse_daily_reliability.unknown_seconds+excluded.unknown_seconds;

            -- Keep a bounded 90-calendar-day window, including today, in the
            -- same transaction. This never removes catalogue/EVSE identities.
            DELETE FROM evse_daily_reliability
            WHERE observation_date <
                ((statement_timestamp() AT TIME ZONE 'Europe/Amsterdam')::date - 89);

            -- A missing source timestamp is not a fresh measurement.  Do not
            -- reinterpret a replayed timestamp or late correction as elapsed time.
            INSERT INTO evse_observation_checkpoint
              (evse_id, status_class, source_updated_at, observed_at, state_started_at, continuity_valid)
            SELECT s.evse_id, s.status_class, s.source_updated_at, statement_timestamp(), NULL, false
            FROM stage_observation s
            WHERE s.source_updated_at IS NOT NULL
            ON CONFLICT (evse_id) DO UPDATE SET
              status_class=excluded.status_class,
              source_updated_at=excluded.source_updated_at,
              observed_at=excluded.observed_at,
              state_started_at=NULL,
              continuity_valid=false
            WHERE excluded.source_updated_at > evse_observation_checkpoint.source_updated_at
               OR evse_observation_checkpoint.source_updated_at IS NULL;

            -- Location availability is determined from the full imported set of
            -- EVSE statuses, not a product of independent EVSE probabilities.
            -- Unknown without a known AVAILABLE makes zero-availability uncertain.
            CREATE TEMP TABLE stage_location_observation ON COMMIT DROP AS
            SELECT location_id, count(*)::integer AS evse_count,
                   count(*) FILTER (WHERE status_class='available')::integer AS available_count,
                   count(*) FILTER (WHERE status_class='fault')::integer AS fault_count,
                   count(*) FILTER (WHERE status_class='unknown')::integer AS unknown_count,
                   CASE WHEN bool_or(status_class='available') THEN true
                     WHEN bool_or(status_class='unknown') THEN NULL
                     ELSE false END AS any_available,
                   min(source_updated_at) AS oldest_source_updated_at
            FROM stage_observation
            GROUP BY location_id;

            -- Only complete known-source snapshots are recorded. A repeated
            -- collection of unchanged EVSE timestamps is not a new observation.
            INSERT INTO location_observation_checkpoint
              (location_id, evse_count, available_count, fault_count, unknown_count,
               any_available, observed_at, zero_available_started_at, continuity_valid)
            SELECT s.location_id, s.evse_count, s.available_count, s.fault_count,
                   s.unknown_count, s.any_available, statement_timestamp(), NULL, false
            FROM stage_location_observation s
            WHERE s.oldest_source_updated_at IS NOT NULL
              AND NOT EXISTS (
                SELECT 1 FROM stage_observation e
                WHERE e.location_id=s.location_id AND e.source_updated_at IS NULL)
              AND EXISTS (
                SELECT 1 FROM stage_fresh_evse f WHERE f.location_id=s.location_id)
              AND NOT EXISTS (
                SELECT 1 FROM stage_stale_location stale
                WHERE stale.location_id=s.location_id)
            ON CONFLICT (location_id) DO UPDATE SET
              evse_count=excluded.evse_count,
              available_count=excluded.available_count,
              fault_count=excluded.fault_count,
              unknown_count=excluded.unknown_count,
              any_available=excluded.any_available,
              observed_at=excluded.observed_at,
              zero_available_started_at=NULL,
              continuity_valid=false
            WHERE (location_observation_checkpoint.evse_count,
                   location_observation_checkpoint.available_count,
                   location_observation_checkpoint.fault_count,
                   location_observation_checkpoint.unknown_count,
                   location_observation_checkpoint.any_available)
              IS DISTINCT FROM
                  (excluded.evse_count, excluded.available_count,
                   excluded.fault_count, excluded.unknown_count, excluded.any_available);
            """;
        const string boundary = "-- A missing source timestamp is not a fresh measurement.";
        var split = sql.IndexOf(boundary, StringComparison.Ordinal);
        if (split < 0) throw new InvalidOperationException("Observation SQL phase boundary is missing.");

        // Phase 1 materializes old/new observations and uptime before checkpoint writes.
        await using (var first = new NpgsqlCommand(sql[..split], db, tx) { CommandTimeout = 600 })
            await first.ExecuteNonQueryAsync();

        // Must use old checkpoints: identical replays add zero seconds.
        await EvseHourPersistence.Capture(db, tx);
        await LocationHourPersistence.Capture(db, tx);

        // Phase 2 advances durable checkpoints and location observations.
        await using var second = new NpgsqlCommand(sql[split..], db, tx) { CommandTimeout = 600 };
        await second.ExecuteNonQueryAsync();
    }
}
