using Npgsql;

// The caller owns one transaction spanning the poll claim, per-entity writes,
// and eventual hourly finalization. Never commit a claim independently.
// Not yet connected to WatchRunner: broad per-poll writes require a load gate.
internal static class SnapshotPollStore
{
    public static async Task<bool> TryClaim(
        NpgsqlConnection connection, NpgsqlTransaction transaction,
        string pollId, DateTimeOffset observedAtUtc, SnapshotFeedQuality quality)
    {
        if (string.IsNullOrWhiteSpace(pollId))
            throw new ArgumentException("A stable poll identity is required.", nameof(pollId));
        const string sql = """
            INSERT INTO snapshot_poll_checkpoint
              (source_name,last_poll_id,last_observed_at,last_quality)
            VALUES ('ndw-locations',$1,$2,$3)
            ON CONFLICT(source_name) DO UPDATE SET
              last_poll_id=excluded.last_poll_id,
              last_observed_at=excluded.last_observed_at,
              last_quality=excluded.last_quality,
              accepted_poll_count=snapshot_poll_checkpoint.accepted_poll_count+1
            WHERE excluded.last_observed_at>snapshot_poll_checkpoint.last_observed_at
              AND excluded.last_poll_id<>snapshot_poll_checkpoint.last_poll_id
            RETURNING 1;
            """;
        await using var command = new NpgsqlCommand(sql, connection, transaction);
        command.Parameters.AddWithValue(pollId);
        command.Parameters.AddWithValue(observedAtUtc.ToUniversalTime());
        command.Parameters.AddWithValue(
            quality == SnapshotFeedQuality.ChangedFeed ? "changed_feed" : "unchanged_feed");
        return await command.ExecuteScalarAsync() is not null;
    }

    public static Task AccumulateEvse(
        NpgsqlConnection connection, NpgsqlTransaction transaction, long evseId,
        DateTimeOffset observedAtUtc, SnapshotEvseCategory category, SnapshotFeedQuality quality)
        => Accumulate(connection, transaction, "evse_snapshot_active_hour", "evse_id",
            evseId, observedAtUtc, (int)category + 1, 9, quality);

    public static Task AccumulateLocation(
        NpgsqlConnection connection, NpgsqlTransaction transaction, long locationId,
        DateTimeOffset observedAtUtc, SnapshotLocationCategory category, SnapshotFeedQuality quality)
        => Accumulate(connection, transaction, "location_snapshot_active_hour", "location_id",
            locationId, observedAtUtc, (int)category + 1, 5, quality);

    private static async Task Accumulate(
        NpgsqlConnection connection, NpgsqlTransaction transaction, string table, string idColumn,
        long entityId, DateTimeOffset observedAtUtc, int categoryIndex, int categoryCount,
        SnapshotFeedQuality quality)
    {
        // Table and column names are selected exclusively by the two fixed internal callers.
        if (entityId <= 0) throw new ArgumentOutOfRangeException(nameof(entityId));
        var utc = observedAtUtc.ToUniversalTime();
        var hour = new DateTimeOffset(utc.Year, utc.Month, utc.Day, utc.Hour, 0, 0, TimeSpan.Zero);
        var counts = new int[categoryCount];
        counts[categoryIndex - 1] = 1;
        var changed = quality == SnapshotFeedQuality.ChangedFeed ? 1 : 0;
        var unchanged = quality == SnapshotFeedQuality.UnchangedFeed ? 1 : 0;
        // The caller must flush a previous completed hour BEFORE adding a new hour.
        // The hour equality guard prevents silent loss of unflushed samples.
        var sql = $"""
            INSERT INTO {table} ({idColumn},hour_start_utc,category_counts,changed_count,unchanged_count)
            VALUES ($1,$2,$3,$4,$5)
            ON CONFLICT({idColumn}) DO UPDATE SET
              category_counts = ARRAY(
                SELECT {table}.category_counts[i] + excluded.category_counts[i]
                FROM generate_series(1,{categoryCount}) i ORDER BY i
              ),
              changed_count={table}.changed_count+excluded.changed_count,
              unchanged_count={table}.unchanged_count+excluded.unchanged_count
            WHERE {table}.hour_start_utc=excluded.hour_start_utc
            RETURNING 1;
            """;
        await using var command = new NpgsqlCommand(sql, connection, transaction);
        command.Parameters.AddWithValue(entityId);
        command.Parameters.AddWithValue(hour);
        command.Parameters.AddWithValue(counts);
        command.Parameters.AddWithValue(changed);
        command.Parameters.AddWithValue(unchanged);
        if (await command.ExecuteScalarAsync() is null)
            throw new InvalidOperationException(
                $"Previous snapshot hour for {table}/{entityId} must be finalized before the next hour.");
    }
}
