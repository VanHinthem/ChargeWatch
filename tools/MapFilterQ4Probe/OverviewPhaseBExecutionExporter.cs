using System.Text.RegularExpressions;

// P2m-F: execute only ONE actual phase-B overview query, using the same
// PREPARE parameter fixture as the successfully inspected P2m-E plan.
// No original-grid and no four-path comparison in this bounded test.
internal static class OverviewPhaseBExecutionExporter
{
    public static string Create()
    {
        var plan = OverviewPhaseBPreparedPlanExporter.Create();
        const string from = "EXPLAIN (VERBOSE, SETTINGS)";
        const string to = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, SUMMARY)";
        if (Regex.Matches(plan, Regex.Escape(from)).Count != 1 ||
            plan.Contains("EXPLAIN (ANALYZE", StringComparison.Ordinal) ||
            !plan.Contains("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY",
                StringComparison.Ordinal) ||
            !plan.Contains("statement_timeout = '5s'", StringComparison.Ordinal) ||
            !plan.Contains("ROLLBACK;", StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected planner script; refusing to execute.");

        var script = plan.Replace(
            "-- P2m-E: planning-only PREPARE/EXPLAIN EXECUTE real phase-B bound parameters",
            "-- P2m-F: ONE bounded read-only phase-B execution (EXPLAIN ANALYZE, 5s timeout)",
            StringComparison.Ordinal).Replace(from, to, StringComparison.Ordinal);

        if (Regex.Matches(script, Regex.Escape(to)).Count != 1 ||
            Regex.Matches(script, @"\bPREPARE cw_phaseb_planner\(").Count != 1 ||
            Regex.Matches(script, @"\bEXECUTE cw_phaseb_planner\(").Count != 1 ||
            !script.Contains("SET ROLE chargewatch_read;", StringComparison.Ordinal) ||
            !script.Contains("ROLLBACK;", StringComparison.Ordinal) ||
            script.Contains("original_ids AS", StringComparison.Ordinal) ||
            script.Contains("phaseb_ids AS", StringComparison.Ordinal))
            throw new InvalidOperationException("Unsafe or unexpectedly duplicated execution script.");

        return script;
    }
}
