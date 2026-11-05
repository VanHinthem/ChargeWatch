// Pure phase C assertions; real PostgreSQL and HTTP coverage run in CI.
internal static class MapOverviewPhaseCSelfTest
{
    public static void Run()
    {
        static void Require(bool okay, string detail)
        {
            if (!okay) throw new InvalidOperationException("Map overview phase C selftest: " + detail);
        }

        var nation = new Bbox(3.0, 50.6, 7.4, 53.8);
        var low = MapOverviewGridSelector.Select(nation, 6);
        var wide = MapOverviewGridSelector.Select(nation, 12);
        Require(low.Level <= 8 && low.Coverage.CellCount <= 500, "national low zoom complete budget");
        Require(wide.Level <= 14 && wide.Coverage.CellCount <= 500, "national high zoom complete budget");
        Require(wide.Level >= low.Level, "more zoom never forces a coarser national level");

        var city = new Bbox(5.3, 51.5, 5.8, 51.9);
        var cityPan = new Bbox(5.30001, 51.50001, 5.80001, 51.90001);
        var initial = MapOverviewGridSelector.Select(city, 10);
        var shifted = MapOverviewGridSelector.Select(cityPan, 10);
        Require(initial.Level == shifted.Level, "tiny pan preserves resolution");
        Require(initial.Coverage.CellCount <= 500, "regional budget");

        var tiny = new Bbox(5.53, 51.75, 5.53001, 51.75001);
        var point = MapOverviewGridSelector.Select(tiny, 22);
        Require(point.Level == 22 && point.Coverage.CellCount <= 500, "high zoom can show locations");
        var global = MapOverviewGridSelector.Select(new Bbox(-180, -80, 180, 80), 22);
        Require(global.Level >= 0 && global.Coverage.CellCount <= 500, "world fits without truncation");

        var constrained = MapOverviewSql.Build(MapFilter.None, constrainToCoverage: true);
        Require(constrained.Contains("s.cx BETWEEN $6 AND $7") &&
                constrained.Contains("s.cy BETWEEN $8 AND $9") &&
                constrained.Contains("l.position && ST_MakeEnvelope", StringComparison.Ordinal),
            "canonical coverage is enforced in the production SQL");
        Require(!MapOverviewSql.Build(MapFilter.None).Contains("s.cx BETWEEN $6 AND $7"),
            "historical read-only parity exporter remains compatible");
        Require(!constrained.Contains("LIMIT 500", StringComparison.OrdinalIgnoreCase),
            "raw locations are not truncated");

        foreach (var bbox in new[] { nation, city, tiny })
        {
            foreach (var zoom in new[] { 0.0, 4.0, 7.0, 12.0, 17.0, 22.0 })
            {
                var chosen = MapOverviewGridSelector.Select(bbox, zoom);
                Require(chosen.Coverage.CellCount <= 500, "all supported zooms fit budget");
                Require(chosen.Level <= Math.Min(22, (int)Math.Floor(zoom) + 2),
                    "selected level never exceeds initial level");
            }
        }

        foreach (var invalid in new[] { double.NaN, double.PositiveInfinity, -1, 23 })
        {
            try
            {
                MapOverviewGridSelector.Select(city, invalid);
                throw new InvalidOperationException("Invalid zoom unexpectedly accepted");
            }
            catch (ArgumentOutOfRangeException) { }
        }
        try
        {
            MapOverviewGridSelector.Select(city, 10, markerBudget: 0);
            throw new InvalidOperationException("Invalid marker budget accepted");
        }
        catch (ArgumentOutOfRangeException) { }

        Console.WriteLine("Map overview phase C selftest passed.");
    }
}
