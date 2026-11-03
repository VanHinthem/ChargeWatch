// P2i: read-only coverage-oriented large-BBOX probe. Retain production
// MapFilter and baseline query, altering only fixed geographic grid parameters.
// These are deliberately separate CLI exports to avoid unguarded national runs.
internal static class OverviewNationalBaselineExporter
{
    public static string Create(string fixture)
    {
        if (fixture != "unfiltered" && fixture != "ccs2_dc")
            throw new ArgumentOutOfRangeException(nameof(fixture));
        var original = OverviewPerformanceExporter.CreateScenario(fixture);
        const string oldEnvelope = "ST_MakeEnvelope(5.40,51.70,5.62,51.83,4326)";
        const string newEnvelope = "ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326)";
        const string oldCell = "40075016.68557849::double precision / 2048.0";
        const string newCell = "40075016.68557849::double precision / 256.0";
        if (original.Split(oldEnvelope, StringSplitOptions.None).Length != 2 ||
            original.Split(oldCell, StringSplitOptions.None).Length != 2)
            throw new InvalidOperationException("National query template mismatch.");
        var sql = original.Replace(oldEnvelope, newEnvelope, StringComparison.Ordinal)
            .Replace(oldCell, newCell, StringComparison.Ordinal);
        if (sql.Split("EXPLAIN (ANALYZE, BUFFERS, SETTINGS)", StringSplitOptions.None).Length != 2 ||
            !sql.Contains(newEnvelope, StringComparison.Ordinal) ||
            !sql.Contains(newCell, StringComparison.Ordinal))
            throw new InvalidOperationException("Expected exactly one national EXPLAIN.");
        return $"-- P2i Netherlands-oriented bbox, gridLevel8, fixture={fixture}; empty cells not emitted\n" + sql;
    }
}
