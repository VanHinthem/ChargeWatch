-- Issue #56: count-based location history alongside existing category probability.
-- Additive only; existing profiles are not backfilled from past percentages.
ALTER TABLE location_snapshot_active_hour
  ADD COLUMN IF NOT EXISTS free_evse_sum bigint NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS active_evse_sum bigint NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS valid_count_samples integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS excluded_count_samples integer NOT NULL DEFAULT 0;
ALTER TABLE location_snapshot_weekday_profile
  ADD COLUMN IF NOT EXISTS available_evse_ewma real[] NOT NULL DEFAULT array_fill(NULL::real,ARRAY[24]),
  ADD COLUMN IF NOT EXISTS active_evse_ewma real[] NOT NULL DEFAULT array_fill(NULL::real,ARRAY[24]),
  ADD COLUMN IF NOT EXISTS valid_count_samples integer[] NOT NULL DEFAULT array_fill(0,ARRAY[24]),
  ADD COLUMN IF NOT EXISTS excluded_count_samples integer[] NOT NULL DEFAULT array_fill(0,ARRAY[24]),
  ADD COLUMN IF NOT EXISTS count_metric_started_at timestamptz,
  ADD COLUMN IF NOT EXISTS count_metric_last_sample_at timestamptz;
DO $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='location_count_arrays_24') THEN
  ALTER TABLE location_snapshot_weekday_profile ADD CONSTRAINT location_count_arrays_24
   CHECK (array_length(available_evse_ewma,1)=24
      AND array_length(active_evse_ewma,1)=24
      AND array_length(valid_count_samples,1)=24
      AND array_length(excluded_count_samples,1)=24);
 END IF;
END $$;
INSERT INTO schema_migration(version) VALUES ('014_location_free_evse_ewma')
ON CONFLICT DO NOTHING;
