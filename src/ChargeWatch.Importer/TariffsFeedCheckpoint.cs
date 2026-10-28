using Npgsql;

// Persist successful NDW Tariffs HTTP checks separately from Locations snapshot provenance.
internal static class TariffsFeedCheckpoint
{
    public static async Task Record(string connectionString, DateTimeOffset checkedAt, bool changed)
    {
        await using var db = new NpgsqlConnection(connectionString);
        await db.OpenAsync();
        await using var cmd = new NpgsqlCommand("""
            INSERT INTO ndw_tariffs_feed_checkpoint
                (feed_key, last_successful_check_at, last_changed_at)
            VALUES ('tariffs', $1, $2)
            ON CONFLICT (feed_key) DO UPDATE SET
                last_successful_check_at = GREATEST(
                    ndw_tariffs_feed_checkpoint.last_successful_check_at,
                    excluded.last_successful_check_at),
                last_changed_at = CASE WHEN excluded.last_changed_at IS NOT NULL
                    THEN GREATEST(
                        ndw_tariffs_feed_checkpoint.last_changed_at,
                        excluded.last_changed_at)
                    ELSE ndw_tariffs_feed_checkpoint.last_changed_at END
            """, db);
        cmd.Parameters.AddWithValue(checkedAt.UtcDateTime);
        cmd.Parameters.AddWithValue(changed ? checkedAt.UtcDateTime : DBNull.Value);
        await cmd.ExecuteNonQueryAsync();
    }
}
