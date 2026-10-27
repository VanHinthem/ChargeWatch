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

          INSERT INTO evse_poll_hour_coverage(evse_id,hour_start_utc,covered_seconds,available_seconds)
          SELECT evse_id,hour_start_utc,sum(seconds)::integer,
                 sum(CASE WHEN status_class='available' THEN seconds ELSE 0 END)::integer
          FROM (
            SELECT evse_id,status_class,h.hour_start_utc,
                   extract(epoch FROM
                     least(end_at,h.hour_start_utc+interval '1 hour')
                     -greatest(start_at,h.hour_start_utc))::integer AS seconds
            FROM poll_eligible
            CROSS JOIN LATERAL generate_series(
              date_trunc('hour',start_at),
              date_trunc('hour',end_at),interval '1 hour') h(hour_start_utc)
          ) intervals
          WHERE seconds>0
          GROUP BY evse_id,hour_start_utc
          ON CONFLICT(evse_id,hour_start_utc) DO UPDATE SET
            covered_seconds=evse_poll_hour_coverage.covered_seconds+excluded.covered_seconds,
            available_seconds=evse_poll_hour_coverage.available_seconds+excluded.available_seconds
          WHERE evse_poll_hour_coverage.covered_seconds + excluded.covered_seconds <= 3600
            AND evse_poll_hour_coverage.available_seconds + excluded.available_seconds <= 3600;
          -- Preserve existing history instead of clipping values to 3600.
          -- Intervals whose union cannot be proven from the existing aggregate
          -- are NOT applied when they would overflow the hour. The caller logs
          -- the number rejected. We still advance the snapshot checkpoint,
          -- allowing the independent catalog import to commit.

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
                    WITH proposed AS (
                      SELECT i.evse_id,h.hour_start_utc,
                             sum(extract(epoch FROM
                               least(i.end_at,h.hour_start_utc+interval '1 hour')
                               -greatest(i.start_at,h.hour_start_utc))::integer) AS seconds,
                             sum(CASE WHEN i.status_class='available' THEN
                               extract(epoch FROM
                                 least(i.end_at,h.hour_start_utc+interval '1 hour')
                                 -greatest(i.start_at,h.hour_start_utc))::integer
                               ELSE 0 END) AS available_seconds
                      FROM poll_eligible i
                      CROSS JOIN LATERAL generate_series(
                        date_trunc('hour',i.start_at),
                        date_trunc('hour',i.end_at),interval '1 hour') h(hour_start_utc)
                      WHERE least(i.end_at,h.hour_start_utc+interval '1 hour')
                            > greatest(i.start_at,h.hour_start_utc)
                      GROUP BY i.evse_id,h.hour_start_utc
                    )
                    SELECT count(*)
                    FROM proposed p
                    JOIN evse_poll_hour_coverage existing USING(evse_id,hour_start_utc)
                    WHERE existing.covered_seconds+p.seconds>3600
                       OR existing.available_seconds+p.available_seconds>3600
                    """, db, tx) { CommandTimeout = 600 };
                var rejected = (long)(await probe.ExecuteScalarAsync())!;
                if (rejected > 0)
                    Console.Error.WriteLine(
                        $"WARNING: {rejected} overlapping/saturated EVSE-hour coverage increments suppressed; undefined");
            }
            await command.ExecuteNonQueryAsync();
        }
    }
}
