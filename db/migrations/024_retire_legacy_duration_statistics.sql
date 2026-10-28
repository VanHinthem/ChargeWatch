-- Retire the legacy source-timestamp-based duration/statistics experiment.
-- The production API uses compact snapshot weekday profiles and separate
-- snapshot_poll_checkpoint / snapshot_active_hour tables (migrations 008-014).
-- Deploy only together with the collector that no longer runs
-- ObservationCheckpoints / old hour finalizers. Pre-migration backup mandatory.
-- This deliberately destroys the unused legacy statistics history.
DROP TABLE IF EXISTS evse_daily_reliability;
DROP TABLE IF EXISTS evse_duration_summary;
DROP TABLE IF EXISTS location_zero_available_summary;
DROP TABLE IF EXISTS evse_occupancy_profile;
DROP TABLE IF EXISTS location_occupancy_profile;
DROP TABLE IF EXISTS evse_hour_accumulator;
DROP TABLE IF EXISTS location_hour_accumulator;
DROP TABLE IF EXISTS evse_observation_checkpoint;
DROP TABLE IF EXISTS location_observation_checkpoint;
INSERT INTO schema_migration(version)
VALUES ('024_retire_legacy_duration_statistics')
ON CONFLICT DO NOTHING;
