using Npgsql;

// Finish persisted UTC hours before accepting samples from a newer UTC hour.
// Caller must hold the same database transaction as SnapshotPollStore.TryClaim
// and subsequent category writes. No independent commit is permitted.
internal static class SnapshotHourFinalizer
{
    public static async Task<(long Evses, long Locations)> CompleteBefore(
        NpgsqlConnection connection, NpgsqlTransaction transaction, DateTimeOffset observedAtUtc)
    {
        // Serialize all statistics writers, including concurrent instances.
        await using (var gate = new NpgsqlCommand(
            "SELECT pg_advisory_xact_lock(24680, 17017)", connection, transaction))
            await gate.ExecuteNonQueryAsync();

        var utc = observedAtUtc.ToUniversalTime();
        var currentHour = new DateTimeOffset(utc.Year, utc.Month, utc.Day, utc.Hour, 0, 0, TimeSpan.Zero);
        var hours = new List<DateTimeOffset>();
        await using (var read = new NpgsqlCommand("""
            SELECT DISTINCT hour_start_utc FROM (
              SELECT hour_start_utc FROM evse_snapshot_active_hour
              UNION ALL
              SELECT hour_start_utc FROM location_snapshot_active_hour
            ) pending
            WHERE hour_start_utc < $1
            ORDER BY hour_start_utc
            """, connection, transaction))
        {
            read.Parameters.AddWithValue(currentHour);
            await using var reader = await read.ExecuteReaderAsync();
            while (await reader.ReadAsync())
                hours.Add(reader.GetFieldValue<DateTimeOffset>(0).ToUniversalTime());
        }

        var zone = TimeZoneInfo.FindSystemTimeZoneById("Europe/Amsterdam");
        long evses = 0, locations = 0;
        foreach (var hour in hours)
        {
            var local = TimeZoneInfo.ConvertTime(hour, zone);
            var weekday = ((int)local.DayOfWeek + 6) % 7 + 1;
            await using var finalize = new NpgsqlCommand(
                "SELECT evse_finalized, location_finalized FROM snapshot_finalize_hours($1,$2,$3,$4)",
                connection, transaction) { CommandTimeout = 600 };
            finalize.Parameters.AddWithValue(hour);
            finalize.Parameters.AddWithValue(weekday);
            finalize.Parameters.AddWithValue(local.Hour);
            finalize.Parameters.AddWithValue(CompactSnapshotStatistics.WeeklySlotAlpha);
            await using var reader = await finalize.ExecuteReaderAsync();
            if (!await reader.ReadAsync())
                throw new InvalidOperationException("Hour finalizer returned no result.");
            evses += reader.GetInt64(0);
            locations += reader.GetInt64(1);
        }
        return (evses, locations);
    }
}
