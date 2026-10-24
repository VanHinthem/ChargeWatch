using Npgsql;

// Optional pre-publication transaction step for an independently verified 200
// Locations fetch. Must run BEFORE any catalog status updates, inside the same
// transaction. Never flush against the new snapshot.
internal static class SnapshotCatalogTransition
{
    public static async Task PrepareBeforeChangedCatalog(
        NpgsqlConnection db, NpgsqlTransaction tx, DateTimeOffset fetchedAtUtc)
    {
        await using (var gate = new NpgsqlCommand(
            "SELECT pg_advisory_xact_lock(24680,17017)", db, tx))
            await gate.ExecuteNonQueryAsync();

        DateTimeOffset? pendingHour = null;
        string? pendingHash = null;
        await using (var command = new NpgsqlCommand("""
            SELECT p.hour_start_utc,p.snapshot_sha256
            FROM snapshot_unchanged_hour p
            WHERE p.source_name='ndw-locations'
            FOR UPDATE
            """, db, tx))
        await using (var reader = await command.ExecuteReaderAsync())
        {
            if (await reader.ReadAsync())
            {
                pendingHour = reader.GetFieldValue<DateTimeOffset>(0);
                pendingHash = reader.GetString(1);
            }
        }

        if (pendingHour is not null)
        {
            // Complete older hours, then apply this buffer to the still-current
            // catalog and retain it if the incoming 200 is within this hour.
            await SnapshotHourFinalizer.CompleteBefore(db, tx, pendingHour.Value);
            await SnapshotUnchangedHourFlusher.Flush(db, tx, pendingHash!);
        }

        // Complete any now-finished hours before publishing the new catalog.
        await SnapshotHourFinalizer.CompleteBefore(db, tx, fetchedAtUtc);
    }
}
