#!/bin/sh
set -eu
# Runs only on initial database creation by the official PostgreSQL entrypoint.
# Credentials are passed to psql via variables, not interpolated into shell SQL.
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v import_password="$CW_IMPORT_PASSWORD" -v read_password="$CW_READ_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE chargewatch_import LOGIN PASSWORD %L', :'import_password') \gexec
SELECT format('CREATE ROLE chargewatch_read LOGIN PASSWORD %L', :'read_password') \gexec
GRANT CONNECT ON DATABASE chargewatch TO chargewatch_import, chargewatch_read;
GRANT USAGE ON SCHEMA public TO chargewatch_import, chargewatch_read;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO chargewatch_import;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO chargewatch_import;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO chargewatch_read;
SQL
