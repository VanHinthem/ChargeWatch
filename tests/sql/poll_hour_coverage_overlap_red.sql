-- Issue #72: RED reproduction of non-idempotent EVSE-hour coverage accumulation.
-- EXPECTED TO FAIL with the current additive ON CONFLICT implementation.
-- Run only against an isolated TEST database:
--   psql -X -v ON_ERROR_STOP=1 -d chargewatch_test -f tests/sql/poll_hour_coverage_overlap_red.sql
-- Uses TEMP tables only and rolls back; never modifies persistent data.
--
-- All intervals are within 2026-10-25 08:00 UTC.
-- A: [08:00,08:10) available; B: [08:05,08:15) available.
-- Correct union: 900 covered/available seconds, not 1200.
-- Replaying B must not change coverage.

BEGIN;
CREATE TEMP TABLE coverage_test (
    evse_id bigint NOT NULL,
    hour_start_utc timestamptz NOT NULL,
    covered_seconds integer NOT NULL CHECK (covered_seconds BETWEEN 0 AND 3600),
    available_seconds integer NOT NULL CHECK (available_seconds BETWEEN 0 AND 3600),
    PRIMARY KEY (evse_id, hour_start_utc),
    CHECK (available_seconds <= covered_seconds)
) ON COMMIT DROP;

CREATE TEMP TABLE interval_test (
    evse_id bigint NOT NULL,
    hour_start_utc timestamptz NOT NULL,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    status_class text NOT NULL
) ON COMMIT DROP;

-- Two valid polls with five minutes' overlap.
INSERT INTO interval_test VALUES
    (1,'2026-10-25 08:00+00','2026-10-25 08:00+00','2026-10-25 08:10+00','available'),
    (1,'2026-10-25 08:00+00','2026-10-25 08:05+00','2026-10-25 08:15+00','available');

-- Reproduce the exact additive UPSERT rule from SnapshotPollCoverage.Capture.
-- A and B are each applied individually, as on separate collector imports.
DO $test$
DECLARE
    sample record;
BEGIN
    FOR sample IN SELECT * FROM interval_test ORDER BY start_at LOOP
        INSERT INTO coverage_test (evse_id,hour_start_utc,covered_seconds,available_seconds)
        VALUES (
            sample.evse_id,
            sample.hour_start_utc,
            extract(epoch FROM sample.end_at - sample.start_at)::integer,
            CASE WHEN sample.status_class='available'
                 THEN extract(epoch FROM sample.end_at - sample.start_at)::integer ELSE 0 END
        )
        ON CONFLICT (evse_id,hour_start_utc) DO UPDATE SET
            covered_seconds=coverage_test.covered_seconds+excluded.covered_seconds,
            available_seconds=coverage_test.available_seconds+excluded.available_seconds
        WHERE coverage_test.covered_seconds+excluded.covered_seconds <= 3600
          AND coverage_test.available_seconds+excluded.available_seconds <= 3600;
    END LOOP;
END
$test$;

-- RED: current implementation produces 1200, while interval union is 900.
DO $assert$
DECLARE
    actual_covered integer;
    actual_available integer;
BEGIN
    SELECT covered_seconds,available_seconds
      INTO actual_covered,actual_available
      FROM coverage_test WHERE evse_id=1 AND hour_start_utc='2026-10-25 08:00+00';
    IF actual_covered <> 900 OR actual_available <> 900 THEN
        RAISE EXCEPTION
            'KNOWN ISSUE #72: overlapping intervals counted twice: got covered=%, available=%; expected 900/900',
            actual_covered,actual_available;
    END IF;
END
$assert$;

ROLLBACK;
