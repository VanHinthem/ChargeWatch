using Npgsql;

internal static class LocationHourPersistence
{
    // Must run before ObservationCheckpoints advances EVSE checkpoints.
    // Every EVSE must have the same old/new source boundary and unchanged
    // known status. This deliberately excludes ambiguous or partial snapshots.
    public static async Task Capture(NpgsqlConnection db, NpgsqlTransaction tx)
    {
        await using var command = new NpgsqlCommand("""
            DO $$
            BEGIN
              IF NOT EXISTS(SELECT 1 FROM schema_migration WHERE version='005_location_hours')
              THEN RAISE EXCEPTION 'Migration 005_location_hours required'; END IF;
            END $$;

            CREATE TEMP TABLE stage_location_valid_interval ON COMMIT DROP AS
            WITH aggregate AS (
              SELECT s.location_id, count(*) AS members,
                count(cp.evse_id) AS prior_members,
                count(*) FILTER (WHERE s.status_class='unknown' OR cp.status_class='unknown'
                    OR s.status_class<>cp.status_class
                    OR s.source_updated_at IS NULL OR cp.source_updated_at IS NULL
                    OR s.source_updated_at<=cp.source_updated_at
                    OR s.source_updated_at>cp.source_updated_at+interval '15 minutes') AS invalid_members,
                min(cp.source_updated_at) AS prior_first,
                max(cp.source_updated_at) AS prior_last,
                min(s.source_updated_at) AS next_first,
                max(s.source_updated_at) AS next_last,
                bool_or(s.status_class='available') AS any_free
              FROM stage_observation s
              LEFT JOIN evse_observation_checkpoint cp ON cp.evse_id=s.evse_id
              GROUP BY s.location_id
            )
            SELECT location_id, prior_first AS starts_at, next_first AS ends_at,
                   any_free
            FROM aggregate
            WHERE members=prior_members AND members>0 AND invalid_members=0
              AND prior_first=prior_last AND next_first=next_last
              AND next_first>prior_first
              AND next_first<=prior_first+interval '15 minutes';

            CREATE TEMP TABLE stage_location_hour_segment ON COMMIT DROP AS
            WITH segments AS (
              SELECT a.location_id,a.any_free,
                     h.utc_hour AS hour_start_utc,
                     greatest(a.starts_at,h.utc_hour) AS starts_at,
                     least(a.ends_at,h.utc_hour+interval '1 hour') AS ends_at
              FROM stage_location_valid_interval a
              CROSS JOIN LATERAL generate_series(
                date_trunc('hour', a.starts_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC',
                date_trunc('hour', a.ends_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC',
                interval '1 hour') h(utc_hour)
            )
            SELECT location_id,hour_start_utc,starts_at,ends_at,
                   extract(epoch FROM ends_at-starts_at)::integer AS covered_seconds,
                   CASE WHEN any_free THEN extract(epoch FROM ends_at-starts_at)::integer ELSE 0 END AS available_seconds
            FROM segments WHERE ends_at>starts_at;

            INSERT INTO location_hour_accumulator
             (location_id,hour_start_utc,first_interval_start,last_interval_end,covered_seconds,available_seconds)
            SELECT location_id,hour_start_utc,starts_at,ends_at,covered_seconds,available_seconds
            FROM stage_location_hour_segment
            ON CONFLICT(location_id,hour_start_utc) DO UPDATE SET
              last_interval_end=excluded.last_interval_end,
              covered_seconds=location_hour_accumulator.covered_seconds+excluded.covered_seconds,
              available_seconds=location_hour_accumulator.available_seconds+excluded.available_seconds
            WHERE location_hour_accumulator.finalized_at IS NULL
              AND excluded.first_interval_start>=location_hour_accumulator.last_interval_end
              AND location_hour_accumulator.covered_seconds+excluded.covered_seconds<=3600;
            """, db, tx) { CommandTimeout = 600 };
        await command.ExecuteNonQueryAsync();
    }
}
