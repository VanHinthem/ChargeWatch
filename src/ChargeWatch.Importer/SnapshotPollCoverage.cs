using Npgsql;

// Candidate polling coverage only. Existing EWMA profiles are untouched.
internal static class SnapshotPollCoverage
{
    public static async Task Capture(NpgsqlConnection db, NpgsqlTransaction tx,
        DateTimeOffset fetchedAt, string sha)
    {
        const string sql = """
          CREATE TEMP TABLE poll_previous ON COMMIT DROP AS
          SELECT fetched_at,source_sha256 FROM ndw_snapshot_checkpoint
          WHERE source_name='ndw-locations';

          CREATE TEMP TABLE poll_eligible ON COMMIT DROP AS
          SELECT s.evse_id,s.status_class,p.fetched_at AS start_at,
                 $1::timestamptz AS end_at
          FROM stage_observation s
          JOIN evse_poll_checkpoint old ON old.evse_id=s.evse_id
          JOIN poll_previous p ON true
          WHERE p.fetched_at<$1::timestamptz
            AND $1::timestamptz<=p.fetched_at+interval '15 minutes'
            AND p.source_sha256<>$2::text
            -- A different gzip hash (or changed location metadata) does not
            -- establish a new EVSE feed observation. Require at least one
            -- changed EVSE source status/timestamp anywhere in the snapshot.
            AND EXISTS (
              SELECT 1 FROM stage_observation feed
              LEFT JOIN evse_poll_checkpoint prev ON prev.evse_id=feed.evse_id
              WHERE prev.evse_id IS NULL
                 OR feed.status_class IS DISTINCT FROM prev.status_class
                 OR feed.source_updated_at IS DISTINCT FROM prev.source_updated_at
            )
            AND old.snapshot_sha256=p.source_sha256
            AND old.observed_at=p.fetched_at
            AND old.status_class=s.status_class
            AND s.status_class IN ('available','occupied','fault')
            AND s.source_updated_at IS NOT NULL
            AND old.source_updated_at IS NOT NULL
            AND s.source_updated_at>=old.source_updated_at
            -- Both samples must be recent relative to their own fetch moment.
            -- A stale unchanged status is not fresh evidence of availability.
            AND old.source_updated_at >= p.fetched_at - interval '24 hours'
            AND old.source_updated_at <= p.fetched_at + interval '5 minutes'
            AND s.source_updated_at >= $1::timestamptz - interval '24 hours'
            AND s.source_updated_at <= $1::timestamptz + interval '5 minutes';

          CREATE TEMP TABLE poll_proposed ON COMMIT DROP AS
          SELECT evse_id,hour_start_utc,
                 sum(microseconds)::bigint AS microseconds,
                 sum(CASE WHEN status_class='available' THEN microseconds ELSE 0 END)::bigint AS available_microseconds
          FROM (
            SELECT evse_id,status_class,h.hour_start_utc,
                   (extract(epoch FROM
                     least(end_at,h.hour_start_utc+interval '1 hour')
                     -greatest(start_at,h.hour_start_utc))*1000000)::bigint AS microseconds
            FROM poll_eligible
            CROSS JOIN LATERAL generate_series(
              date_trunc('hour',start_at),
              date_trunc('hour',end_at),interval '1 hour') h(hour_start_utc)
          ) intervals
          WHERE microseconds>0
          GROUP BY evse_id,hour_start_utc;

          INSERT INTO evse_poll_hour_coverage
            (evse_id,hour_start_utc,covered_seconds,available_seconds,
             covered_microseconds,available_microseconds)
          SELECT evse_id,hour_start_utc,
                 (microseconds/1000000)::integer,
                 (available_microseconds/1000000)::integer,
                 microseconds,available_microseconds
          FROM poll_proposed
          ON CONFLICT(evse_id,hour_start_utc) DO UPDATE SET
            covered_microseconds=evse_poll_hour_coverage.covered_microseconds+excluded.covered_microseconds,
            available_microseconds=evse_poll_hour_coverage.available_microseconds+excluded.available_microseconds,
            covered_seconds=((evse_poll_hour_coverage.covered_microseconds+excluded.covered_microseconds)/1000000)::integer,
            available_seconds=((evse_poll_hour_coverage.available_microseconds+excluded.available_microseconds)/1000000)::integer
          -- Legacy rows have no exact provenance: preserve them as legacy.
          WHERE evse_poll_hour_coverage.covered_microseconds IS NOT NULL
            AND evse_poll_hour_coverage.covered_microseconds+excluded.covered_microseconds <= 3600000000
            AND evse_poll_hour_coverage.available_microseconds+excluded.available_microseconds <= 3600000000;

          INSERT INTO evse_poll_checkpoint
            (evse_id,observed_at,status_class,source_updated_at,snapshot_sha256)
          SELECT s.evse_id,$1::timestamptz,s.status_class,s.source_updated_at,$2::text
          FROM stage_observation s
          WHERE NOT EXISTS(SELECT 1 FROM poll_previous
                           WHERE fetched_at >= $1::timestamptz
                              OR source_sha256=$2::text)
          ON CONFLICT(evse_id) DO UPDATE SET
            observed_at=excluded.observed_at,status_class=excluded.status_class,
            source_updated_at=excluded.source_updated_at,snapshot_sha256=excluded.snapshot_sha256
          WHERE evse_poll_checkpoint.observed_at<excluded.observed_at;
          """;
        // PostgreSQL does not allow multi-statement prepared commands.
        // Execute each statement separately while sharing transaction-scoped temp tables.
        foreach (var statement in sql.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            await using var command = new NpgsqlCommand(statement, db, tx) { CommandTimeout = 600 };
            command.Parameters.AddWithValue(fetchedAt.ToUniversalTime());
            command.Parameters.AddWithValue(sha);
            if (statement.StartsWith("INSERT INTO evse_poll_hour_coverage", StringComparison.Ordinal))
            {
                // Detect attempted increments that would exceed the actual hour.
                // Existing aggregate counters do not reveal the covered time spans,
                // so overlapping seconds cannot be deduplicated with confidence.
                await using var probe = new NpgsqlCommand("""
                    SELECT
                      count(*) FILTER(WHERE existing.covered_microseconds IS NULL) AS legacy_rows,
                      count(*) FILTER(WHERE existing.covered_microseconds IS NOT NULL
                        AND existing.covered_microseconds+p.microseconds>3600000000) AS overflows
                    FROM poll_proposed p
                    JOIN evse_poll_hour_coverage existing USING(evse_id,hour_start_utc)
                    """, db, tx) { CommandTimeout = 600 };
                await using var reader = await probe.ExecuteReaderAsync();
                await reader.ReadAsync();
                var legacy = reader.GetInt64(0);
                var overflow = reader.GetInt64(1);
                await reader.DisposeAsync();
                if (legacy > 0)
                    Console.Error.WriteLine(
                        $"INFO: {legacy} legacy EVSE-hour increments skipped; historical seconds preserved.");
                if (overflow > 0)
                    Console.Error.WriteLine(
                        $"WARNING: {overflow} exact EVSE-hour coverage increments exceed 3600 seconds; existing history preserved.");
            }
            await command.ExecuteNonQueryAsync();
        }
    }
}
