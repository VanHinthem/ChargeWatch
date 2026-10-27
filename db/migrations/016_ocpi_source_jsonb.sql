-- Preserve current raw NDW OCPI attributes without duplicating nested child trees.
-- Nullable: existing rows are backfilled by an explicit subsequent full Locations import.
ALTER TABLE charging_location ADD COLUMN IF NOT EXISTS source_data jsonb;
ALTER TABLE evse ADD COLUMN IF NOT EXISTS source_data jsonb;
ALTER TABLE connector ADD COLUMN IF NOT EXISTS source_data jsonb;

INSERT INTO schema_migration(version) VALUES ('016_ocpi_source_jsonb');
