\set ON_ERROR_STOP on
BEGIN;
INSERT INTO charging_location(country_code,party_id,source_id,address,city,is_active)
VALUES ('NL','TST','CONNECTOR-PRESENCE','CI','Oss',true);
INSERT INTO evse(location_id,source_uid,status,is_active)
SELECT id,'TEST-EVSE','AVAILABLE',true FROM charging_location WHERE source_id='CONNECTOR-PRESENCE';
INSERT INTO connector(evse_id,source_id,standard,is_active)
SELECT id,'REMOVED','IEC_62196_T2',true FROM evse WHERE source_uid='TEST-EVSE';
INSERT INTO connector(evse_id,source_id,standard,is_active)
SELECT id,'PRESENT','IEC_62196_T2',true FROM evse WHERE source_uid='TEST-EVSE';
-- Preserve historical tariff relations when connector becomes inactive.
INSERT INTO connector_tariff(connector_id,tariff_country_code,tariff_party_id,tariff_source_id)
SELECT id,'NL','TST','HISTORICAL' FROM connector WHERE source_id='REMOVED';

CREATE TEMP TABLE stage_present_connector_ids ON COMMIT DROP AS
SELECT id FROM connector WHERE source_id='PRESENT';
CREATE UNIQUE INDEX ON stage_present_connector_ids(id);

CREATE TEMP TABLE stage_connector_presence_excluded_evse_ids(id bigint PRIMARY KEY)
ON COMMIT DROP;

SELECT * FROM reconcile_connector_snapshot_presence('2026-10-29 08:00Z');
SELECT * FROM reconcile_connector_snapshot_presence('2026-10-29 08:00Z');
DO $verify$
BEGIN
 IF (SELECT consecutive_missing_snapshots FROM connector WHERE source_id='REMOVED')<>1
 THEN RAISE EXCEPTION 'replayed snapshot incremented missing evidence'; END IF;
END $verify$;

SELECT * FROM reconcile_connector_snapshot_presence('2026-10-29 08:05Z');
DO $verify$
BEGIN
 IF (SELECT NOT is_active OR consecutive_missing_snapshots<>2 FROM connector WHERE source_id='REMOVED')
 THEN RAISE EXCEPTION 'connector deactivated too early'; END IF;
END $verify$;
SELECT * FROM reconcile_connector_snapshot_presence('2026-10-29 08:10Z');
DO $verify$
BEGIN
 IF (SELECT is_active OR consecutive_missing_snapshots<>3 FROM connector WHERE source_id='REMOVED')
 THEN RAISE EXCEPTION 'connector not deactivated at threshold'; END IF;
 IF (SELECT count(*) FROM connector_tariff ct JOIN connector c ON c.id=ct.connector_id WHERE c.source_id='REMOVED')<>1
 THEN RAISE EXCEPTION 'historical connector tariff lost'; END IF;
 IF (SELECT count(*) FROM connector WHERE is_active AND source_id='PRESENT')<>1
 THEN RAISE EXCEPTION 'present connector incorrectly deactivated'; END IF;
END $verify$;

INSERT INTO stage_present_connector_ids(id) SELECT id FROM connector WHERE source_id='REMOVED';
SELECT * FROM reconcile_connector_snapshot_presence('2026-10-29 08:15Z');
DO $verify$
BEGIN
 IF (SELECT NOT is_active OR consecutive_missing_snapshots<>0 OR first_missing_at IS NOT NULL
     FROM connector WHERE source_id='REMOVED')
 THEN RAISE EXCEPTION 'returning connector not reactivated'; END IF;
END $verify$;
ROLLBACK;
