ALTER TABLE connector ADD COLUMN IF NOT EXISTS format text;
ALTER TABLE connector ADD COLUMN IF NOT EXISTS max_voltage integer;
ALTER TABLE connector ADD COLUMN IF NOT EXISTS max_amperage integer;

INSERT INTO schema_migration(version) VALUES ('017_connector_electrical_fields');
