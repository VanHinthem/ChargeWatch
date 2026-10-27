-- Isolated PostgreSQL regression for the coverage overflow guard.
-- Run with psql -v ON_ERROR_STOP=1 -f ... against a TEST database.
-- Uses a temporary table, never touches persistent production data.
BEGIN;
CREATE TEMP TABLE coverage_test (
  evse_id bigint NOT NULL,
  hour_start_utc timestamptz NOT NULL,
  covered_seconds int NOT NULL CHECK (covered_seconds BETWEEN 0 AND 3600),
  available_seconds int NOT NULL CHECK (available_seconds BETWEEN 0 AND 3600),
  PRIMARY KEY (evse_id, hour_start_utc),
  CHECK (available_seconds <= covered_seconds)
) ON COMMIT DROP;

INSERT INTO coverage_test VALUES
  (1, '2026-10-27 13:00:00+00', 3600, 3600),
  (2, '2026-10-27 13:00:00+00', 3500, 1500);

CREATE TEMP TABLE incoming_test (
  evse_id bigint,
  hour_start_utc timestamptz,
  covered_seconds int,
  available_seconds int
) ON COMMIT DROP;
INSERT INTO incoming_test VALUES
  (1, '2026-10-27 13:00:00+00', 60, 60),
  (2, '2026-10-27 13:00:00+00', 30, 15),
  (3, '2026-10-27 13:00:00+00', 120, 60);

INSERT INTO coverage_test(evse_id,hour_start_utc,covered_seconds,available_seconds)
SELECT evse_id,hour_start_utc,covered_seconds,available_seconds FROM incoming_test
ON CONFLICT(evse_id,hour_start_utc) DO UPDATE SET
  covered_seconds=coverage_test.covered_seconds+excluded.covered_seconds,
  available_seconds=coverage_test.available_seconds+excluded.available_seconds
WHERE coverage_test.covered_seconds+excluded.covered_seconds <= 3600
  AND coverage_test.available_seconds+excluded.available_seconds <= 3600;

DO $check$
BEGIN
  IF (SELECT count(*) FROM coverage_test) <> 3 THEN
    RAISE EXCEPTION 'Coverage fixture row count mismatch';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM coverage_test WHERE evse_id=1
      AND covered_seconds=3600 AND available_seconds=3600) THEN
    RAISE EXCEPTION 'Saturated row was modified';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM coverage_test WHERE evse_id=2
      AND covered_seconds=3530 AND available_seconds=1515) THEN
    RAISE EXCEPTION 'Safe increment was not applied';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM coverage_test WHERE evse_id=3
      AND covered_seconds=120 AND available_seconds=60) THEN
    RAISE EXCEPTION 'New hour row not created';
  END IF;
  RAISE NOTICE 'Coverage overflow guard regression passed';
END
$check$;

ROLLBACK;
