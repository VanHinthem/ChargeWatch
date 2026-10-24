using Npgsql;

// Compress verified HTTP 304 responses into one durable UTC-hour counter.
// The caller MUST flush the pending hour against its matching catalog snapshot
// before changing source SHA or advancing the hour. No silent rollover.
internal static class SnapshotUnchangedHourStore
{
    public static async Task<bool> Record(
        NpgsqlConnection connection, NpgsqlTransaction transaction,
        string pollId, DateTimeOffset observedAtUtc, string snapshotSha256)
    {
        if (snapshotSha256.Length != 64 || !snapshotSha256.All(Uri.IsHexDigit))
            throw new ArgumentException("A verified SHA-256 is required.", nameof(snapshotSha256));
        // Claim plus increment must be atomic; Serialize against finalization.
        if (!await SnapshotPollStore.TryClaim(connection, transaction, pollId, observedAtUtc,
                SnapshotFeedQuality.UnchangedFeed))
            return false;

        var utc = observedAtUtc.ToUniversalTime();
        var hour = new DateTimeOffset(utc.Year, utc.Month, utc.Day, utc.Hour, 0, 0, TimeSpan.Zero);
        await using var cmd = new NpgsqlCommand("""
            INSERT INTO snapshot_unchanged_hour
             (source_name,hour_start_utc,snapshot_sha256,poll_count,first_observed_at,last_observed_at)
            VALUES ('ndw-locations',$1,$2,1,$3,$3)
            ON CONFLICT(source_name) DO UPDATE SET
              poll_count=snapshot_unchanged_hour.poll_count+1,
              last_observed_at=excluded.last_observed_at
            WHERE snapshot_unchanged_hour.hour_start_utc=excluded.hour_start_utc
              AND snapshot_unchanged_hour.snapshot_sha256=excluded.snapshot_sha256
              AND snapshot_unchanged_hour.last_observed_at<excluded.last_observed_at
            RETURNING 1
            """, connection, transaction);
        cmd.Parameters.AddWithValue(hour);
        cmd.Parameters.AddWithValue(snapshotSha256.ToLowerInvariant());
        cmd.Parameters.AddWithValue(utc);
        if (await cmd.ExecuteScalarAsync() is null)
            throw new InvalidOperationException(
                "Pending unchanged hour must be flushed against the original snapshot before rollover.");
        return true;
    }
}
