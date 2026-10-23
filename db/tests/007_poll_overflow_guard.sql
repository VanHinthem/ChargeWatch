-- Test the same CHECK guard used by SnapshotPollCoverage's ON CONFLICT update.
-- Transaction rolls back; no persistent test coverage is added.
BEGIN;
DO $$
DECLARE existing_evse bigint;
BEGIN
  SELECT id INTO existing_evse FROM evse ORDER BY id LIMIT 1;
  IF existing_evse IS NULL THEN RAISE EXCEPTION 'No EVSE fixture in test database'; END IF;
  INSERT INTO evse_poll_hour_coverage(evse_id,hour_start_utc,covered_seconds,available_seconds)
  VALUES (existing_evse, '2099-01-01T00:00:00Z', 3599, 100)
  ON CONFLICT (evse_id,hour_start_utc) DO UPDATE
  SET covered_seconds=3599,available_seconds=100;
  BEGIN
    INSERT INTO evse_poll_hour_coverage(evse_id,hour_start_utc,covered_seconds,available_seconds)
    VALUES (existing_evse, '2099-01-01T00:00:00Z', 2, 0)
    ON CONFLICT (evse_id,hour_start_utc) DO UPDATE SET
      covered_seconds=evse_poll_hour_coverage.covered_seconds+excluded.covered_seconds,
      available_seconds=evse_poll_hour_coverage.available_seconds+excluded.available_seconds;
    RAISE EXCEPTION 'Overflow was silently accepted';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'PASS: overflow rejected with check_violation';
  END;
END $$;
ROLLBACK;
