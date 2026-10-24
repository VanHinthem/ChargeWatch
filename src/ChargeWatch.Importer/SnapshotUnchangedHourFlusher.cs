using Npgsql;

// Call before replacing the committed Locations snapshot with HTTP 200 catalog
// updates, and before UTC-hour rollover. The connection/transaction must be
// the SAME as the corresponding catalog update and hour finalization.
// This method does not open, commit, or deploy anything.
internal static class SnapshotUnchangedHourFlusher
{
    public static async Task<(long Evses, long Locations, int Polls)> Flush(
        NpgsqlConnection connection, NpgsqlTransaction transaction, string expectedSnapshotSha256)
    {
        if (expectedSnapshotSha256.Length != 64 ||
            !expectedSnapshotSha256.All(Uri.IsHexDigit))
            throw new ArgumentException("Expected a verified SHA-256.", nameof(expectedSnapshotSha256));

        await using var command = new NpgsqlCommand(
            "SELECT evses_written,locations_written,polls_applied FROM snapshot_flush_unchanged_hour($1)",
            connection, transaction) { CommandTimeout = 600 };
        command.Parameters.AddWithValue(expectedSnapshotSha256.ToLowerInvariant());
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync())
            throw new InvalidOperationException("Unchanged-hour flush did not return a result.");
        return (reader.GetInt64(0), reader.GetInt64(1), reader.GetInt32(2));
    }
}
