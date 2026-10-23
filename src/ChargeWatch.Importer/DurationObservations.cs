using Npgsql;

// Tracks confirmed, contiguous same-state runs. NDW status transitions have
// unknown change timestamps, therefore completed-duration histogram buckets
// are NOT inferred. Interrupted runs are censored.
internal static class DurationObservations
{
    public static async Task Capture(NpgsqlConnection db, NpgsqlTransaction tx)
    {
        await using var cmd = new NpgsqlCommand("""
            CREATE TEMP TABLE stage_duration_transition ON COMMIT DROP AS
            SELECT s.evse_id, s.status_class, s.source_updated_at,
                   cp.status_class AS previous_class,
                   cp.source_updated_at AS previous_at,
                   cp.state_started_at AS previous_start,
                   coalesce(cp.continuity_valid,false) AS previous_valid,
                   s.source_updated_at IS NOT NULL
                     AND cp.source_updated_at IS NOT NULL
                     AND s.source_updated_at>cp.source_updated_at
                     AND s.source_updated_at<=cp.source_updated_at+interval '15 minutes'
                     AND s.status_class=cp.status_class AS continues,
                   s.source_updated_at IS NOT NULL
                     AND cp.source_updated_at IS NOT NULL
                     AND s.source_updated_at>cp.source_updated_at
                     AND s.status_class<>cp.status_class AS changed
            FROM stage_observation s
            JOIN evse_observation_checkpoint cp ON cp.evse_id=s.evse_id
            WHERE s.source_updated_at>cp.source_updated_at;

            INSERT INTO evse_duration_summary(evse_id,transitions,censored_periods)
            SELECT evse_id,
                   CASE WHEN changed THEN 1 ELSE 0 END,
                   CASE WHEN previous_valid AND NOT continues THEN 1 ELSE 0 END
            FROM stage_duration_transition
            WHERE changed OR (previous_valid AND NOT continues)
            ON CONFLICT(evse_id) DO UPDATE
              SET transitions=evse_duration_summary.transitions+excluded.transitions,
                  censored_periods=evse_duration_summary.censored_periods+excluded.censored_periods;
            """,db,tx){CommandTimeout=600};
        await cmd.ExecuteNonQueryAsync();
    }
}
