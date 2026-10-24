using Npgsql;

// Opt-in 304-only ingest. No catalog rewrite, no per-asset writes per poll.
// The request identity is generated once per successful HTTP response, not
// derived from a source hash (unchanged snapshots still represent new polls).
internal static class SnapshotUnchangedPollRecorder
{
    public static async Task<bool> Record(
        string connectionString, string pollId, DateTimeOffset observedAtUtc,
        string cachedSnapshotSha256)
    {
        if (cachedSnapshotSha256.Length != 64 || !cachedSnapshotSha256.All(Uri.IsHexDigit))
            throw new ArgumentException("Verified cached feed SHA-256 required.", nameof(cachedSnapshotSha256));
        await using var db = new NpgsqlConnection(connectionString);
        await db.OpenAsync();
        await using var tx = await db.BeginTransactionAsync();
        try
        {
            await using (var gate = new NpgsqlCommand(
                "SELECT pg_advisory_xact_lock(24680,17017)", db, tx))
                await gate.ExecuteNonQueryAsync();

            string currentHash;
            if (!string.Equals(currentHash, cachedSnapshotSha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(
                    "304 cache SHA does not match committed catalog; reject observation and refetch Locations.");
            DateTimeOffset? pendingHour = null;
            string? pendingHash = null;

            await using (var current = new NpgsqlCommand("""
                SELECT source_sha256 FROM ndw_snapshot_checkpoint
                WHERE source_name='ndw-locations'
                """, db, tx))
            {
                currentHash = (string?)await current.ExecuteScalarAsync()
                    ?? throw new InvalidOperationException("No committed NDW Locations snapshot exists.");
            }

            await using (var pending = new NpgsqlCommand("""
                SELECT hour_start_utc,snapshot_sha256
                FROM snapshot_unchanged_hour
                WHERE source_name='ndw-locations' FOR UPDATE
                """, db, tx))
            await using (var reader = await pending.ExecuteReaderAsync())
            {
                if (await reader.ReadAsync())
                {
                    pendingHour = reader.GetFieldValue<DateTimeOffset>(0);
                    pendingHash = reader.GetString(1);
                }
            }

            var utc = observedAtUtc.ToUniversalTime();
            var hour = new DateTimeOffset(utc.Year, utc.Month, utc.Day, utc.Hour, 0, 0, TimeSpan.Zero);
            if (pendingHour is not null &&
                (pendingHour != hour || !string.Equals(pendingHash, currentHash, StringComparison.OrdinalIgnoreCase)))
            {
                // Flush using the original committed snapshot. A mismatch fails
                // rather than attributing old 304 observations to a new feed.
                await SnapshotHourFinalizer.CompleteBefore(db, tx, pendingHour.Value);
                await SnapshotUnchangedHourFlusher.Flush(db, tx, pendingHash!);
            }
            await SnapshotHourFinalizer.CompleteBefore(db, tx, observedAtUtc);

            var accepted = await SnapshotUnchangedHourStore.Record(
                db, tx, pollId, observedAtUtc, currentHash);
            await tx.CommitAsync();
            return accepted;
        }
        catch
        {
            await tx.RollbackAsync();
            throw;
        }
    }
}
