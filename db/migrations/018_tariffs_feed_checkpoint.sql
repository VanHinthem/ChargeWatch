CREATE TABLE IF NOT EXISTS ndw_tariffs_feed_checkpoint (
    feed_key text PRIMARY KEY CHECK (feed_key = 'tariffs'),
    last_successful_check_at timestamptz NOT NULL,
    last_changed_at timestamptz
);

GRANT SELECT ON ndw_tariffs_feed_checkpoint TO chargewatch_read;

INSERT INTO schema_migration(version) VALUES ('018_tariffs_feed_checkpoint');
