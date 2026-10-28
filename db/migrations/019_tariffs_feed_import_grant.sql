-- Migration 018 granted API reads but omitted the collector import role.
-- Apply as a new migration because 018 has already run on deployed databases.
GRANT SELECT, INSERT, UPDATE ON ndw_tariffs_feed_checkpoint TO chargewatch_import;

INSERT INTO schema_migration(version) VALUES ('019_tariffs_feed_import_grant');
