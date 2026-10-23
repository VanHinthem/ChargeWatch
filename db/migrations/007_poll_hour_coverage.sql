-- Independent snapshot-based coverage; does not change published 168-slot profiles.
CREATE TABLE IF NOT EXISTS evse_poll_hour_coverage (
    evse_id bigint NOT NULL REFERENCES evse(id) ON DELETE CASCADE,
    hour_start_utc timestamptz NOT NULL,
    covered_seconds integer NOT NULL DEFAULT 0 CHECK (covered_seconds BETWEEN 0 AND 3600),
    available_seconds integer NOT NULL DEFAULT 0 CHECK (available_seconds BETWEEN 0 AND 3600),
    PRIMARY KEY(evse_id, hour_start_utc),
    CHECK(available_seconds<=covered_seconds)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON evse_poll_hour_coverage TO chargewatch_import;
GRANT SELECT ON evse_poll_hour_coverage TO chargewatch_read;
INSERT INTO schema_migration(version) VALUES ('007_poll_hour_coverage') ON CONFLICT DO NOTHING;
