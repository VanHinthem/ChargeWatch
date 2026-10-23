using Npgsql;

// Location probability is measured from the joint state, never multiplied from
// independent EVSE probabilities. Finalize only complete source-covered hours.
internal static class LocationHourFinalizer
{
    public static async Task Run(NpgsqlConnection db, NpgsqlTransaction tx)
    {
        await using var cmd = new NpgsqlCommand("""
            DO $$
            DECLARE
              h record;
              idx integer;
              sample double precision;
            BEGIN
              FOR h IN
                SELECT a.location_id,a.hour_start_utc,a.covered_seconds,a.available_seconds
                FROM location_hour_accumulator a
                WHERE a.finalized_at IS NULL
                  AND a.hour_start_utc+interval '1 hour'<=statement_timestamp()
                  AND EXISTS (
                     SELECT 1 FROM stage_location_valid_interval s
                     WHERE s.location_id=a.location_id
                       AND s.ends_at>=a.hour_start_utc+interval '1 hour')
                ORDER BY a.hour_start_utc,a.location_id FOR UPDATE OF a
              LOOP
                IF h.covered_seconds>=2700 THEN
                  idx := (extract(isodow FROM h.hour_start_utc AT TIME ZONE 'Europe/Amsterdam')::integer-1)*24
                       + extract(hour FROM h.hour_start_utc AT TIME ZONE 'Europe/Amsterdam')::integer+1;
                  sample := h.available_seconds::double precision/h.covered_seconds;
                  INSERT INTO location_occupancy_profile(location_id)
                  VALUES(h.location_id) ON CONFLICT(location_id) DO NOTHING;
                  UPDATE location_occupancy_profile p
                  SET available_ewma[idx]=CASE WHEN p.sample_count[idx]=0 THEN sample
                                              ELSE 0.2*sample+0.8*p.available_ewma[idx] END,
                      sample_count[idx]=p.sample_count[idx]+1,
                      covered_seconds[idx]=p.covered_seconds[idx]+h.covered_seconds,
                      last_sample_at=h.hour_start_utc+interval '1 hour'
                  WHERE p.location_id=h.location_id;
                END IF;
                UPDATE location_hour_accumulator
                SET finalized_at=statement_timestamp()
                WHERE location_id=h.location_id AND hour_start_utc=h.hour_start_utc;
              END LOOP;
            END $$;
            """, db, tx) { CommandTimeout = 600 };
        await cmd.ExecuteNonQueryAsync();
    }
}
