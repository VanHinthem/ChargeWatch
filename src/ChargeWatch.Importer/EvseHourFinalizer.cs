using Npgsql;

// Close each completed UTC hour once. Only a sufficiently covered hour writes
// one sample to the ISO-weekday/local-hour EWMA profile.
internal static class EvseHourFinalizer
{
    public static async Task Run(NpgsqlConnection db, NpgsqlTransaction tx)
    {
        await using var command = new NpgsqlCommand("""
            DO $$
            DECLARE
              hour_row record;
              slot_index integer;
              fraction double precision;
            BEGIN
              FOR hour_row IN
                SELECT evse_id, hour_start_utc, covered_seconds, available_seconds
                FROM evse_hour_accumulator
                WHERE finalized_at IS NULL
                  AND hour_start_utc + interval '1 hour' <= statement_timestamp()
                ORDER BY hour_start_utc, evse_id
                FOR UPDATE
              LOOP
                IF hour_row.covered_seconds >= 2700 THEN
                  slot_index :=
                    (extract(isodow FROM hour_row.hour_start_utc AT TIME ZONE 'Europe/Amsterdam')::integer - 1) * 24
                    + extract(hour FROM hour_row.hour_start_utc AT TIME ZONE 'Europe/Amsterdam')::integer + 1;
                  fraction := hour_row.available_seconds::double precision / hour_row.covered_seconds;

                  INSERT INTO evse_occupancy_profile(evse_id)
                  VALUES (hour_row.evse_id)
                  ON CONFLICT (evse_id) DO NOTHING;

                  UPDATE evse_occupancy_profile p
                  SET ewma[slot_index] =
                        CASE WHEN p.sample_count[slot_index] = 0 THEN fraction
                             ELSE 0.2 * fraction + 0.8 * p.ewma[slot_index] END,
                      sample_count[slot_index] = p.sample_count[slot_index] + 1,
                      covered_seconds[slot_index] = p.covered_seconds[slot_index] + hour_row.covered_seconds,
                      last_sample_at = hour_row.hour_start_utc + interval '1 hour'
                  WHERE p.evse_id = hour_row.evse_id;
                END IF;

                -- Even insufficient hours are closed: they cannot be replayed
                -- or silently treated as sufficient during later imports.
                UPDATE evse_hour_accumulator
                SET finalized_at = statement_timestamp()
                WHERE evse_id = hour_row.evse_id
                  AND hour_start_utc = hour_row.hour_start_utc;
              END LOOP;
            END $$;
            """, db, tx) { CommandTimeout = 600 };
        await command.ExecuteNonQueryAsync();
    }
}
