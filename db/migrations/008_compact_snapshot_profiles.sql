-- Compact snapshot-driven aggregate profiles for PR #17.
-- No raw poll/history rows: at most seven aggregate rows per EVSE/location.
-- Flat category arrays use [category * 24 + local_hour + 1].
CREATE TABLE IF NOT EXISTS evse_snapshot_weekday_profile (
    evse_id bigint NOT NULL REFERENCES evse(id) ON DELETE CASCADE,
    iso_weekday smallint NOT NULL CHECK (iso_weekday BETWEEN 1 AND 7),
    -- 9 categories x 24 local hours, float4 is sufficient for probabilities.
    category_ewma real[] NOT NULL DEFAULT array_fill(NULL::real, ARRAY[216]),
    sample_count integer[] NOT NULL DEFAULT array_fill(0, ARRAY[24]),
    changed_feed_count integer[] NOT NULL DEFAULT array_fill(0, ARRAY[24]),
    unchanged_feed_count integer[] NOT NULL DEFAULT array_fill(0, ARRAY[24]),
    last_sample_at timestamptz,
    algorithm_version smallint NOT NULL DEFAULT 1,
    PRIMARY KEY(evse_id, iso_weekday),
    CHECK (array_length(category_ewma,1)=216),
    CHECK (array_length(sample_count,1)=24),
    CHECK (array_length(changed_feed_count,1)=24),
    CHECK (array_length(unchanged_feed_count,1)=24)
);

CREATE TABLE IF NOT EXISTS location_snapshot_weekday_profile (
    location_id bigint NOT NULL REFERENCES charging_location(id) ON DELETE CASCADE,
    iso_weekday smallint NOT NULL CHECK (iso_weekday BETWEEN 1 AND 7),
    -- 5 location categories x 24 local hours.
    category_ewma real[] NOT NULL DEFAULT array_fill(NULL::real, ARRAY[120]),
    sample_count integer[] NOT NULL DEFAULT array_fill(0, ARRAY[24]),
    changed_feed_count integer[] NOT NULL DEFAULT array_fill(0, ARRAY[24]),
    unchanged_feed_count integer[] NOT NULL DEFAULT array_fill(0, ARRAY[24]),
    last_sample_at timestamptz,
    algorithm_version smallint NOT NULL DEFAULT 1,
    PRIMARY KEY(location_id, iso_weekday),
    CHECK (array_length(category_ewma,1)=120),
    CHECK (array_length(sample_count,1)=24),
    CHECK (array_length(changed_feed_count,1)=24),
    CHECK (array_length(unchanged_feed_count,1)=24)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON
    evse_snapshot_weekday_profile,
    location_snapshot_weekday_profile
TO chargewatch_import;

GRANT SELECT ON
    evse_snapshot_weekday_profile,
    location_snapshot_weekday_profile
TO chargewatch_read;

INSERT INTO schema_migration(version)
VALUES ('008_compact_snapshot_profiles')
ON CONFLICT DO NOTHING;
