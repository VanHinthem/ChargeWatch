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
            AND old.snapshot_sha256=p.source_sha256
            AND old.observed_at=p.fetched_at
            AND old.status_class=s.status_class
            AND s.status_class IN ('available','occupied','fault')
            AND s.source_updated_at IS NOT NULL
            AND old.source_updated_at IS NOT NULL
            AND s.source_updated_at>=old.source_updated_at;

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
          WHERE evse_poll_hour_coverage.covered_seconds+excluded.covered_seconds<=3600;

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
        await using var command = new NpgsqlCommand(sql, db, tx) { CommandTimeout = 600 };
        command.Parameters.AddWithValue(fetchedAt.ToUniversalTime());
        command.Parameters.AddWithValue(sha);
        await command.ExecuteNonQueryAsync();
    }
}
