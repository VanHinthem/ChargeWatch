CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE charging_location (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  country_code text NOT NULL,
  party_id text NOT NULL,
  source_id text NOT NULL,
  address text NOT NULL DEFAULT '',
  city text NOT NULL DEFAULT '',
  operator_name text,
  latitude double precision,
  longitude double precision,
  position geography(Point,4326),
  source_last_updated timestamptz,
  CONSTRAINT location_identity UNIQUE (country_code, party_id, source_id),
  CONSTRAINT location_lat CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
  CONSTRAINT location_lon CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);
CREATE INDEX ix_location_position ON charging_location USING gist(position);

CREATE TABLE evse (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  location_id bigint NOT NULL REFERENCES charging_location(id),
  source_uid text NOT NULL,
  external_evse_id text,
  status text NOT NULL,
  source_last_updated timestamptz,
  CONSTRAINT evse_identity UNIQUE (location_id, source_uid)
);

CREATE TABLE connector (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  evse_id bigint NOT NULL REFERENCES evse(id),
  source_id text NOT NULL,
  standard text,
  power_type text,
  max_electric_power integer,
  CONSTRAINT connector_identity UNIQUE (evse_id, source_id)
);

CREATE TABLE tariff (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  country_code text NOT NULL,
  party_id text NOT NULL,
  source_id text NOT NULL,
  currency text,
  details jsonb NOT NULL,
  source_last_updated timestamptz,
  CONSTRAINT tariff_identity UNIQUE (country_code, party_id, source_id)
);

CREATE TABLE connector_tariff (
  connector_id bigint NOT NULL REFERENCES connector(id),
  tariff_country_code text NOT NULL,
  tariff_party_id text NOT NULL,
  tariff_source_id text NOT NULL,
  PRIMARY KEY (connector_id, tariff_country_code, tariff_party_id, tariff_source_id)
);
CREATE INDEX ix_connector_tariff_lookup ON connector_tariff(tariff_country_code, tariff_party_id, tariff_source_id);

CREATE TABLE import_run (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  source_name text NOT NULL,
  source_sha256 text,
  status text NOT NULL,
  location_count integer,
  evse_count integer,
  connector_count integer,
  error_message text
);
