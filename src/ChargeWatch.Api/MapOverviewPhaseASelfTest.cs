using System.Text.Json;

internal static class MapOverviewPhaseASelfTest
{
    public static void Run()
    {
        static void Require(bool condition, string detail)
        {
            if (!condition) throw new InvalidOperationException("Map overview phase A selftest: " + detail);
        }

        var oss = MapGrid.CellAt(5.53, 51.75, 11);
        var ossBounds = MapGrid.Bounds(oss);
        Require(oss.X >= 0 && oss.Y >= 0, "positive Oss grid indices");
        Require(5.53 >= ossBounds.MinLon && 5.53 <= ossBounds.MaxLon &&
                51.75 >= ossBounds.MinLat && 51.75 <= ossBounds.MaxLat,
                "point inside own canonical cell bounds");
        var middle = MapGrid.Center(oss);
        Require(MapGrid.CellAt(middle.Longitude, middle.Latitude, 11) == oss,
                "cell center resolves to same canonical cell");
        var sameCell = MapGrid.CellAt(middle.Longitude + 0.00001, middle.Latitude, 11);
        Require(sameCell == oss, "small camera movement does not change cell identity");

        var regional = new Bbox(5.20, 51.60, 5.90, 52.10);
        var tiles = MapGrid.Cover(regional, 10);
        Require(tiles.Contains(MapGrid.CellAt(5.53, 51.75, 10)), "Oss in regional coverage");
        Require(tiles.CellCount > 0, "nonempty canonical coverage");

        var padded = MapGrid.Cover(regional, 10, bufferCells: 1);
        Require(padded.MinCellX <= tiles.MinCellX && padded.MinCellY <= tiles.MinCellY &&
                padded.MaxCellX >= tiles.MaxCellX && padded.MaxCellY >= tiles.MaxCellY,
                "buffer adds whole canonical cells");
        Require(MapGrid.Cover(regional, 10) == tiles, "coverage deterministic");

        // Longitude and latitude at canonical corners are bounded. Two
        // separate neighboring cell centers must never share ownership.
        var neighbor = new MapGridCell(oss.Level, oss.X + 1, oss.Y);
        var otherCenter = MapGrid.Center(neighbor);
        Require(MapGrid.CellAt(otherCenter.Longitude, otherCenter.Latitude, 11) == neighbor &&
                neighbor != oss, "neighbor has unique ownership");

        var world = MapGrid.Cover(new Bbox(-180, -80, 180, 80), 0);
        Require(world.CellCount == 1 && world.MinCellX == 0 && world.MinCellY == 0,
                "global level0 covers exactly one world cell");
        Require(MapGrid.CellAt(-180, 0, 8).X == 0 &&
                MapGrid.CellAt(180, 0, 8).X == 255, "world longitudinal extrema clamp safely");
        try
        {
            MapGrid.Cover(new Bbox(5.9, 51.6, 5.2, 52.1), 10);
            throw new InvalidOperationException("Invalid bbox unexpectedly allowed");
        }
        catch (ArgumentOutOfRangeException) { }
        try
        {
            MapGrid.CellAt(double.NaN, 51.7, 10);
            throw new InvalidOperationException("NaN unexpectedly allowed");
        }
        catch (ArgumentOutOfRangeException) { }

        var cluster = new MapOverviewClusterMarker(oss.Level, oss.X, oss.Y,
            middle.Longitude, middle.Latitude, 100, 70, 15, 15);
        Require(cluster.HasValidPartition, "three location-status partitions total 100");
        Require(!(cluster with { LocationsUnknown = 14 }).HasValidPartition,
                "invalid status partition rejected");
        MapOverviewMarker marker = new MapOverviewLocationMarker(oss.Level, oss.X, oss.Y,
            5.53, 51.75, "NL-ABC-example", 1, 2, "known");
        var jsonOptions = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        using var markerJson = JsonDocument.Parse(JsonSerializer.Serialize(marker, jsonOptions));
        Require(markerJson.RootElement.GetProperty("kind").GetString() == "location" &&
                markerJson.RootElement.GetProperty("featureId").GetString() == "NL-ABC-example",
                "existing OCPI feature identity remains string and kind is emitted");
        using var clusterJson = JsonDocument.Parse(JsonSerializer.Serialize<MapOverviewMarker>(cluster, jsonOptions));
        Require(clusterJson.RootElement.GetProperty("kind").GetString() == "cluster" &&
                clusterJson.RootElement.GetProperty("locationCount").GetInt32() == 100,
                "cluster discriminated JSON shape");

        var coverage = MapOverviewCoverage.FromGrid(tiles, complete: true);
        Require(coverage.Complete && coverage.GridLevel == 10, "coverage metadata and scope");
        Console.WriteLine("Map overview phase A selftest passed.");
    }
}
