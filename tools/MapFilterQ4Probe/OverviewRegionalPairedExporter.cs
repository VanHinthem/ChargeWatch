// P2h: comparable regional P2a vs P2f repeated plans. All statements
// originate in existing production-MapFilter-backed exporters.
internal static class OverviewRegionalPairedExporter
{
    public static string Create()
    {
        var original = OverviewPerformanceExporter.CreateScenario("ccs2_dc");
        const string oldEnvelope = "ST_MakeEnvelope(5.40,51.70,5.62,51.83,4326)";
        const string newEnvelope = "ST_MakeEnvelope(5.20,51.60,5.90,52.10,4326)";
        const string oldCell = "40075016.68557849::double precision / 2048.0";
        const string newCell = "40075016.68557849::double precision / 1024.0";
        if (original.Split(oldEnvelope, StringSplitOptions.None).Length != 2 ||
            original.Split(oldCell, StringSplitOptions.None).Length != 2)
            throw new InvalidOperationException("Original regional replacement marker must occur once.");
        original = original.Replace(oldEnvelope, newEnvelope, StringComparison.Ordinal)
            .Replace(oldCell, newCell, StringComparison.Ordinal);

        var indexed = OverviewGeoFirstExporter.CreateMediumRegionCcs2Dc();
        const string expectedGeo = "ST_MakeEnvelope(5.20,51.60,5.90,52.10,4326)";
        const string expectedCell = "40075016.68557849::double precision/1024.0";
        if (!original.Contains(expectedGeo, StringComparison.Ordinal) ||
            !original.Contains(newCell, StringComparison.Ordinal) ||
            !indexed.Contains(expectedGeo, StringComparison.Ordinal) ||
            !indexed.Contains(expectedCell, StringComparison.Ordinal))
            throw new InvalidOperationException("Regional grid fixture mismatch.");
        if (original.Contains("ST_MakeEnvelope(5.40", StringComparison.Ordinal) ||
            indexed.Contains("ST_MakeEnvelope(5.40", StringComparison.Ordinal))
            throw new InvalidOperationException("Old Oss bbox unexpectedly remains.");
        const string explain = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS)";
        if (original.Split(explain, StringSplitOptions.None).Length != 2 ||
            indexed.Split(explain, StringSplitOptions.None).Length != 2)
            throw new InvalidOperationException("Exactly one performance EXPLAIN per run expected.");

        var trials = new (string Variant, string Sql)[]
        {
            ("original", original), ("indexed", indexed),
            ("indexed", indexed), ("original", original)
        };
        return string.Join("\n\n", trials.Select((trial, i) =>
            $"\\echo P2h region run={i + 1} variant={trial.Variant}\n" + trial.Sql));
    }
}
