\set ON_ERROR_STOP on

DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'charging_location','evse','connector','connector_tariff','tariff','import_run',
    'location_snapshot_weekday_profile','evse_snapshot_weekday_profile'
  ]
  LOOP
    IF NOT has_table_privilege('chargewatch_read',t,'SELECT') THEN
      RAISE EXCEPTION 'chargewatch_read lacks SELECT on %',t;
    END IF;
    IF has_table_privilege('chargewatch_read',t,'INSERT')
       OR has_table_privilege('chargewatch_read',t,'UPDATE')
       OR has_table_privilege('chargewatch_read',t,'DELETE')
       OR has_table_privilege('chargewatch_read',t,'TRUNCATE') THEN
      RAISE EXCEPTION 'chargewatch_read has write privilege on %',t;
    END IF;
  END LOOP;
END $$;

SET ROLE chargewatch_read;
SELECT id,country_code,party_id,source_id,address,city,operator_name,latitude,longitude,source_last_updated
FROM charging_location LIMIT 0;
SELECT id,location_id,source_uid,external_evse_id,status,source_last_updated FROM evse LIMIT 0;
SELECT id,evse_id,source_id,standard,power_type,max_electric_power FROM connector LIMIT 0;
SELECT connector_id,tariff_country_code,tariff_party_id,tariff_source_id FROM connector_tariff LIMIT 0;
SELECT country_code,party_id,source_id,currency,details,source_last_updated FROM tariff LIMIT 0;
SELECT completed_at,status FROM import_run LIMIT 0;
SELECT location_id,iso_weekday,category_ewma,sample_count,changed_feed_count,unchanged_feed_count,last_sample_at
FROM location_snapshot_weekday_profile LIMIT 0;
SELECT evse_id,iso_weekday,category_ewma,sample_count,changed_feed_count,unchanged_feed_count,last_sample_at
FROM evse_snapshot_weekday_profile LIMIT 0;
RESET ROLE;

\echo 'Read-only API role smoke passed.'
