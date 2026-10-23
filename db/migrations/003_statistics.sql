-- Sprint 3 additive migration. Apply with psql -v ON_ERROR_STOP=1 -1 as database owner.
-- Safe to rerun. No existing table is dropped or truncated.
CREATE TABLE IF NOT EXISTS schema_migration (
    version text PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS evse_observation_checkpoint (
    evse_id bigint PRIMARY KEY REFERENCES evse(id) ON DELETE CASCADE,
    status_class text NOT NULL CHECK (status_class IN ('available','occupied','fault','unknown')),
    source_updated_at timestamptz,
    observed_at timestamptz NOT NULL,
    -- A change without an observable start is not a completed duration.
    state_started_at timestamptz,
    continuity_valid boolean NOT NULL DEFAULT false
);

CREATE TABLE IF NOT EXISTS location_observation_checkpoint (
    location_id bigint PRIMARY KEY REFERENCES charging_location(id) ON DELETE CASCADE,
    evse_count integer NOT NULL CHECK (evse_count >= 0),
    available_count integer NOT NULL CHECK (available_count >= 0),
    fault_count integer NOT NULL CHECK (fault_count >= 0),
    unknown_count integer NOT NULL CHECK (unknown_count >= 0),
    any_available boolean,
    observed_at timestamptz NOT NULL,
    zero_available_started_at timestamptz,
    continuity_valid boolean NOT NULL DEFAULT false,
    CHECK (available_count + fault_count + unknown_count <= evse_count),
    CHECK (any_available IS DISTINCT FROM false OR unknown_count = 0)
);

-- Fixed 168-slot arrays (ISO weekday * 24). Null EWMA = insufficient data.
CREATE TABLE IF NOT EXISTS evse_occupancy_profile (
    evse_id bigint PRIMARY KEY REFERENCES evse(id) ON DELETE CASCADE,
    ewma double precision[] NOT NULL DEFAULT array_fill(NULL::double precision, ARRAY[168]),
    sample_count integer[] NOT NULL DEFAULT array_fill(0, ARRAY[168]),
    covered_seconds bigint[] NOT NULL DEFAULT array_fill(0::bigint, ARRAY[168]),
    last_sample_at timestamptz,
    CHECK (array_length(ewma,1)=168 AND array_length(sample_count,1)=168 AND array_length(covered_seconds,1)=168)
);

CREATE TABLE IF NOT EXISTS location_occupancy_profile (
    location_id bigint PRIMARY KEY REFERENCES charging_location(id) ON DELETE CASCADE,
    available_ewma double precision[] NOT NULL DEFAULT array_fill(NULL::double precision, ARRAY[168]),
    sample_count integer[] NOT NULL DEFAULT array_fill(0, ARRAY[168]),
    covered_seconds bigint[] NOT NULL DEFAULT array_fill(0::bigint, ARRAY[168]),
    last_sample_at timestamptz,
    CHECK (array_length(available_ewma,1)=168 AND array_length(sample_count,1)=168 AND array_length(covered_seconds,1)=168)
);

CREATE TABLE IF NOT EXISTS evse_daily_reliability (
    evse_id bigint NOT NULL REFERENCES evse(id) ON DELETE CASCADE,
    observation_date date NOT NULL,
    operating_seconds bigint NOT NULL DEFAULT 0 CHECK (operating_seconds >= 0),
    fault_seconds bigint NOT NULL DEFAULT 0 CHECK (fault_seconds >= 0),
    unknown_seconds bigint NOT NULL DEFAULT 0 CHECK (unknown_seconds >= 0),
    PRIMARY KEY(evse_id, observation_date)
);
CREATE INDEX IF NOT EXISTS ix_evse_daily_reliability_date ON evse_daily_reliability(observation_date);

-- Buckets: <15, <30, <60, <120, <240, >=240 minutes.
CREATE TABLE IF NOT EXISTS evse_duration_summary (
    evse_id bigint PRIMARY KEY REFERENCES evse(id) ON DELETE CASCADE,
    available_buckets bigint[] NOT NULL DEFAULT array_fill(0::bigint, ARRAY[6]),
    occupied_buckets bigint[] NOT NULL DEFAULT array_fill(0::bigint, ARRAY[6]),
    fault_buckets bigint[] NOT NULL DEFAULT array_fill(0::bigint, ARRAY[6]),
    transitions bigint NOT NULL DEFAULT 0,
    censored_periods bigint NOT NULL DEFAULT 0,
    CHECK (array_length(available_buckets,1)=6 AND array_length(occupied_buckets,1)=6 AND array_length(fault_buckets,1)=6)
);

CREATE TABLE IF NOT EXISTS location_zero_available_summary (
    location_id bigint PRIMARY KEY REFERENCES charging_location(id) ON DELETE CASCADE,
    zero_available_buckets bigint[] NOT NULL DEFAULT array_fill(0::bigint, ARRAY[6]),
    recovered_periods bigint NOT NULL DEFAULT 0,
    censored_periods bigint NOT NULL DEFAULT 0,
    CHECK (array_length(zero_available_buckets,1)=6)
);

-- Existing roles were granted table-level permissions only at DB initialization.
GRANT SELECT, INSERT, UPDATE, DELETE ON
    evse_observation_checkpoint, location_observation_checkpoint,
    evse_occupancy_profile, location_occupancy_profile,
    evse_daily_reliability, evse_duration_summary,
    location_zero_available_summary
TO chargewatch_import;
GRANT SELECT ON
    evse_observation_checkpoint, location_observation_checkpoint,
    evse_occupancy_profile, location_occupancy_profile,
    evse_daily_reliability, evse_duration_summary,
    location_zero_available_summary
TO chargewatch_read;
-- Importer checks this version before writing observations; it must be readable.
GRANT SELECT ON schema_migration TO chargewatch_import;
INSERT INTO schema_migration(version) VALUES ('003_statistics') ON CONFLICT DO NOTHING;
