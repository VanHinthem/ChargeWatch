// Repeat only two fixed, validated MapFilter fixtures; no database connection.
// Each EXPLAIN has its own read-only transaction. P2b parity passed in P2b.
internal static class OverviewPairedExporter
{
    public static string Create()
    {
        var scenarios = new[] { "ccs2_dc", "ccs2_dc_minfree1" };
        var blocks = new List<string>();
        foreach (var scenario in scenarios)
        {
            var original = OverviewPerformanceExporter.CreateScenario(scenario);
            var geoFirst = OverviewGeoFirstExporter.CreateScenario(scenario, includeParity: false);
            var sequence = new (string Variant, string Sql)[]
            {
                ("original", original),
                ("geofirst", geoFirst),
                ("geofirst", geoFirst),
                ("original", original)
            };
            for (var i = 0; i < sequence.Length; i++)
            {
                var run = sequence[i];
                // psql is the documented runner. A visible marker precedes
                // each EXPLAIN so the corresponding plan can be identified.
                blocks.Add($"\\echo P2c scenario={scenario} run={i + 1} variant={run.Variant}\n" + run.Sql);
            }
        }
        return string.Join("\n\n", blocks);
    }
}
