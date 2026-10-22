using Npgsql;

// Executed inside the same transaction as the national catalogue import.
// Deliberately records only new source observations: repeated snapshots do not invent durations.
internal static class ObservationCheckpoints
{
    public static async Task Capture(NpgsqlConnection db, NpgsqlTransaction tx)
    {
        await using var command = new NpgsqlCommand("""
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

            -- Conservative reliability observation: only unchanged state with
            -- two genuinely successive source updates separated by <=15 min.
            -- Crossing a UTC date boundary is left uncounted pending daily splitting.
            CREATE TEMP TABLE stage_reliability_interval ON COMMIT DROP AS
            SELECT s.evse_id, s.status_class,
                   (s.source_updated_at AT TIME ZONE 'UTC')::date AS day_utc,
                   floor(extract(epoch FROM (s.source_updated_at - cp.source_updated_at)))::bigint AS seconds
            FROM stage_observation s
            JOIN evse_observation_checkpoint cp ON cp.evse_id=s.evse_id
            WHERE s.source_updated_at > cp.source_updated_at
              AND s.source_updated_at <= cp.source_updated_at + interval '15 minutes'
              AND s.status_class = cp.status_class
              AND (s.source_updated_at AT TIME ZONE 'UTC')::date =
                  (cp.source_updated_at AT TIME ZONE 'UTC')::date;

            INSERT INTO evse_daily_reliability
              (evse_id, observation_date, operating_seconds, fault_seconds, unknown_seconds)
            SELECT evse_id, day_utc,
                   CASE WHEN status_class IN ('available','occupied') THEN seconds ELSE 0 END,
                   CASE WHEN status_class = 'fault' THEN seconds ELSE 0 END,
                   CASE WHEN status_class = 'unknown' THEN seconds ELSE 0 END
            FROM stage_reliability_interval
            ON CONFLICT (evse_id, observation_date) DO UPDATE SET
              operating_seconds=evse_daily_reliability.operating_seconds + excluded.operating_seconds,
              fault_seconds=evse_daily_reliability.fault_seconds + excluded.fault_seconds,
              unknown_seconds=evse_daily_reliability.unknown_seconds + excluded.unknown_seconds;

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
            """, db, tx) { CommandTimeout = 600 };
        await command.ExecuteNonQueryAsync();
    }
}
