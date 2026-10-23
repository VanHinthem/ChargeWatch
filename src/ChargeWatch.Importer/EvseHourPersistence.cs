using Npgsql;

// Called before checkpoint upserts, within the bulk import transaction.
// Source last_updated is conservatively interpreted; same-status changes only.
// This accumulates coverage, NOT published probabilities.
internal static class EvseHourPersistence
{
    public static async Task Capture(NpgsqlConnection db, NpgsqlTransaction tx)
    {
        await using var cmd = new NpgsqlCommand("""
            DO $$
            BEGIN
              IF NOT EXISTS (SELECT 1 FROM schema_migration WHERE version='004_hour_accumulator')
              THEN RAISE EXCEPTION 'Migration 004_hour_accumulator is required';
              END IF;
            END $$;

            CREATE TEMP TABLE stage_valid_hour_intervals ON COMMIT DROP AS
            WITH candidate AS (
              SELECT s.evse_id, s.status_class,
                     cp.source_updated_at AS begins_at,
                     s.source_updated_at AS ends_at
              FROM stage_observation s
              JOIN evse_observation_checkpoint cp ON cp.evse_id=s.evse_id
              WHERE s.source_updated_at > cp.source_updated_at
                AND s.source_updated_at <= cp.source_updated_at + interval '15 minutes'
                AND s.status_class=cp.status_class
                AND s.status_class <> 'unknown'
            ), hours AS (
              SELECT c.evse_id, c.status_class,
                     h.hour_start AS hour_start_utc,
                     greatest(c.begins_at, h.hour_start) AS first_interval_start,
                     least(c.ends_at, h.hour_start + interval '1 hour') AS last_interval_end
              FROM candidate c
              CROSS JOIN LATERAL generate_series(
                (date_trunc('hour', c.begins_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'),
                (date_trunc('hour', c.ends_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'),
                interval '1 hour') AS h(hour_start)
            )
            SELECT evse_id, hour_start_utc, first_interval_start, last_interval_end,
                   extract(epoch FROM (last_interval_end-first_interval_start))::integer AS covered_seconds,
                   CASE WHEN status_class='available'
                     THEN extract(epoch FROM (last_interval_end-first_interval_start))::integer
                     ELSE 0 END AS available_seconds
            FROM hours
            WHERE last_interval_end > first_interval_start;

            INSERT INTO evse_hour_accumulator
              (evse_id,hour_start_utc,first_interval_start,last_interval_end,
               covered_seconds,available_seconds)
            SELECT evse_id,hour_start_utc,first_interval_start,last_interval_end,
                   covered_seconds,available_seconds
            FROM stage_valid_hour_intervals
            ON CONFLICT (evse_id,hour_start_utc) DO UPDATE SET
              last_interval_end=excluded.last_interval_end,
              covered_seconds=evse_hour_accumulator.covered_seconds+excluded.covered_seconds,
              available_seconds=evse_hour_accumulator.available_seconds+excluded.available_seconds
            WHERE evse_hour_accumulator.finalized_at IS NULL
              AND excluded.first_interval_start >= evse_hour_accumulator.last_interval_end
              AND evse_hour_accumulator.covered_seconds+excluded.covered_seconds <= 3600;
            """, db, tx) { CommandTimeout = 600 };
        await cmd.ExecuteNonQueryAsync();
    }
}
