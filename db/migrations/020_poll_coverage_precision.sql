-- #72 Exact poll coverage for new UTC hours; existing legacy seconds remain unchanged.
ALTER TABLE evse_poll_hour_coverage
  ADD COLUMN covered_microseconds bigint,
  ADD COLUMN available_microseconds bigint;
ALTER TABLE evse_poll_hour_coverage
  ADD CONSTRAINT evse_poll_microseconds_both_or_none
  CHECK ((covered_microseconds IS NULL) = (available_microseconds IS NULL)),
  ADD CONSTRAINT evse_poll_microseconds_bounds
  CHECK (covered_microseconds IS NULL OR (
    covered_microseconds BETWEEN 0 AND 3600000000
    AND available_microseconds BETWEEN 0 AND covered_microseconds
  ));
-- A null precision pair explicitly denotes legacy coverage.
INSERT INTO schema_migration(version) VALUES ('020_poll_coverage_precision');
