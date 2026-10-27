-- Issue #63: retain disappeared location rows and all dependent historical IDs.
-- Default true preserves catalog visibility until the first verified full Locations snapshot.
ALTER TABLE charging_location
  ADD COLUMN IF NOT EXISTS is_active boolean NOT NULL DEFAULT true;

CREATE INDEX IF NOT EXISTS ix_charging_location_active_position
  ON charging_location USING gist(position) WHERE is_active AND position IS NOT NULL;

INSERT INTO schema_migration(version) VALUES ('015_location_activity');
