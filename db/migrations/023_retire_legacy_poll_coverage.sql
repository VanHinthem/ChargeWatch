-- Retire the legacy interval-based EVSE poll coverage experiment.
-- Current API statistics use compact EVSE/location weekday profiles instead.
-- Run ONLY with the corresponding importer image that no longer calls
-- SnapshotPollCoverage.Capture. The existing pre-migration backup is mandatory.
-- Historical interval coverage and its per-EVSE checkpoint are intentionally
-- removed. Snapshot poll/checkpoint and compact statistics are preserved.
DROP TABLE IF EXISTS evse_poll_hour_coverage;
DROP TABLE IF EXISTS evse_poll_checkpoint;
INSERT INTO schema_migration(version)
VALUES ('023_retire_legacy_poll_coverage')
ON CONFLICT DO NOTHING;
