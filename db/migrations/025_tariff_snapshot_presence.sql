-- Track presence in the latest accepted full NDW Tariffs HTTP 200 snapshot.
-- Historical payloads and connector references remain available for diagnosis,
-- but only active rows may supply current API price components.
ALTER TABLE tariff ADD COLUMN IF NOT EXISTS is_active boolean NOT NULL DEFAULT true;
ALTER TABLE tariff ADD COLUMN IF NOT EXISTS last_seen_at timestamptz;
CREATE INDEX IF NOT EXISTS idx_tariff_active_identity
  ON tariff(country_code,party_id,source_id) WHERE is_active;

CREATE TABLE IF NOT EXISTS ndw_tariffs_snapshot_checkpoint (
  feed_key text PRIMARY KEY CHECK (feed_key='tariffs'),
  source_sha256 text NOT NULL,
  fetched_at timestamptz NOT NULL,
  tariff_count integer NOT NULL CHECK (tariff_count > 0),
  committed_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

GRANT SELECT, INSERT, UPDATE ON ndw_tariffs_snapshot_checkpoint TO chargewatch_import;
GRANT SELECT ON ndw_tariffs_snapshot_checkpoint TO chargewatch_read;

INSERT INTO schema_migration(version)
VALUES ('025_tariff_snapshot_presence')
ON CONFLICT DO NOTHING;
