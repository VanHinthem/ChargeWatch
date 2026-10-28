-- Issue #72: checkpoint-chain behavior with isolated fixtures.
-- This test reproduces the key eligibility predicates in SnapshotPollCoverage.Capture.
-- It does NOT execute the C# importer; full importer integration remains follow-up.
-- psql -X -v ON_ERROR_STOP=1 -d <isolated_test_database> -f tests/sql/poll_checkpoint_chain_regression.sql
-- TEMP tables only; transaction rolled back.
BEGIN;

CREATE TEMP TABLE snapshot_checkpoint_test (
  source_sha256 text PRIMARY KEY, fetched_at timestamptz NOT NULL
) ON COMMIT DROP;
CREATE TEMP TABLE evse_checkpoint_test (
  evse_id bigint PRIMARY KEY, observed_at timestamptz NOT NULL,
  status_class text NOT NULL, source_updated_at timestamptz,
  snapshot_sha256 text NOT NULL
) ON COMMIT DROP;
CREATE TEMP TABLE coverage_test (
  evse_id bigint NOT NULL, hour_start_utc timestamptz NOT NULL,
  covered_seconds integer NOT NULL, available_seconds integer NOT NULL,
  PRIMARY KEY (evse_id,hour_start_utc),
  CHECK (covered_seconds BETWEEN 0 AND 3600),
  CHECK (available_seconds BETWEEN 0 AND covered_seconds)
) ON COMMIT DROP;

INSERT INTO snapshot_checkpoint_test VALUES
 ('a','2026-10-25 08:00+00');
INSERT INTO evse_checkpoint_test VALUES
 (1,'2026-10-25 08:00+00','available','2026-10-25 07:59+00','a');

-- Ordinary consecutive polls: 08:00 -> 08:10, then 08:10 -> 08:20.
-- The importer derives each start_at from the last accepted global checkpoint.
CREATE TEMP TABLE poll_cases (
  label text, evse_id bigint, previous_sha text, previous_at timestamptz,
  next_sha text, next_at timestamptz, old_status text, new_status text,
  expected_eligible boolean
) ON COMMIT DROP;
INSERT INTO poll_cases VALUES
 ('first',1,'a','2026-10-25 08:00+00','b','2026-10-25 08:10+00','available','available',true),
 ('second',1,'b','2026-10-25 08:10+00','c','2026-10-25 08:20+00','available','available',true),
 ('replayed-earlier',1,'c','2026-10-25 08:20+00','b','2026-10-25 08:10+00','available','available',false),
 ('same-hash',1,'c','2026-10-25 08:20+00','c','2026-10-25 08:30+00','available','available',false),
 ('status-changed',1,'c','2026-10-25 08:20+00','d','2026-10-25 08:30+00','available','occupied',false),
 ('gap-over-15min',1,'c','2026-10-25 08:20+00','d','2026-10-25 08:40+00','available','available',false);

DO $assert$
DECLARE wrong integer;
BEGIN
 SELECT count(*) INTO wrong FROM poll_cases
 WHERE (
   previous_at < next_at
   AND next_at <= previous_at + interval '15 minutes'
   AND previous_sha <> next_sha
   AND old_status = new_status
 ) IS DISTINCT FROM expected_eligible;
 IF wrong <> 0 THEN RAISE EXCEPTION 'Checkpoint eligibility regression: % case(s) disagree',wrong; END IF;
END $assert$;

INSERT INTO coverage_test VALUES (1,'2026-10-25 08:00+00',600,600);
INSERT INTO coverage_test(evse_id,hour_start_utc,covered_seconds,available_seconds)
VALUES (1,'2026-10-25 08:00+00',600,600)
ON CONFLICT (evse_id,hour_start_utc) DO UPDATE SET
 covered_seconds=coverage_test.covered_seconds+excluded.covered_seconds,
 available_seconds=coverage_test.available_seconds+excluded.available_seconds
WHERE coverage_test.covered_seconds+excluded.covered_seconds<=3600
 AND coverage_test.available_seconds+excluded.available_seconds<=3600;

DO $assert$
BEGIN
 IF (SELECT covered_seconds FROM coverage_test WHERE evse_id=1) <> 1200 THEN
   RAISE EXCEPTION 'Sequential intervals should produce 1200 seconds';
 END IF;
 RAISE NOTICE 'Checkpoint chain fixture passed: sequential polls do not overlap; replay/status-change predicates reject candidates';
END $assert$;
ROLLBACK;
