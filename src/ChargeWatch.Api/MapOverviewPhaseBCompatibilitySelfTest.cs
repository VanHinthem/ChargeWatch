using System.Text.Json;

// Pure C# compatibility guard; does not open a database or enable the API.
// Read-only NAS probes verify the real PostgreSQL status aggregates separately.
internal static class MapOverviewPhaseBCompatibilitySelfTest
{
    public static void Run()
    {
        static void Require(bool okay, string message)
        {
            if (!okay) throw new InvalidOperationException(
                "Map overview phase B compatibility selftest: " + message);
        }

        var sql = MapOverviewSql.Build(MapFilter.None);
        Require(sql.Contains("WHERE upper(e.status)='AVAILABLE'", StringComparison.Ordinal),
            "available-count SQL matches existing /map/viewport condition");
        Require(sql.Contains("WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('REMOVED','PLANNED')",
                StringComparison.Ordinal),
            "total-count SQL matches existing /map/viewport condition");
        Require(sql.Contains("WHERE free_evses>0)", StringComparison.Ordinal),
            "known free EVSE must classify location as green");
        Require(sql.Contains("WHERE free_evses=0 AND total_evses>0)", StringComparison.Ordinal),
            "zero free and positive total must classify location as red");
        Require(sql.Contains("WHERE total_evses=0)", StringComparison.Ordinal),
            "zero operational EVSEs must classify as unknown");
        Require(!sql.Contains("unclassified_evses", StringComparison.Ordinal),
            "unrecognized EVSE status must not override location color");

        var noEvse = MapOverviewLocationMarker.FromCounts(
            8,130,82,5.53,51.75,"NL-ABC-zero",0,0);
        Require(noEvse.AvailableCount == 0 && noEvse.TotalCount == 0 &&
                noEvse.CountQuality == "unknown", "0/0 is numeric but unknown");
        var free = MapOverviewLocationMarker.FromCounts(
            8,130,82,5.53,51.75,"NL-ABC-free",1,2);
        var busy = MapOverviewLocationMarker.FromCounts(
            8,130,82,5.53,51.75,"NL-ABC-busy",0,2);
        Require(free.AvailableCount == 1 && free.TotalCount == 2 &&
                free.CountQuality == "known", "free location retains known counts");
        Require(busy.AvailableCount == 0 && busy.TotalCount == 2 &&
                busy.CountQuality == "known", "busy location retains known counts");

        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        using (var json = JsonDocument.Parse(
            JsonSerializer.Serialize<MapOverviewMarker>(noEvse, options)))
        {
            var marker = json.RootElement;
            Require(marker.GetProperty("kind").GetString() == "location" &&
                    marker.GetProperty("availableCount").GetInt32() == 0 &&
                    marker.GetProperty("totalCount").GetInt32() == 0 &&
                    marker.GetProperty("countQuality").GetString() == "unknown",
                "0/0 is preserved in wire JSON, never nullable or question mark");
        }
        // Serialize a complete HTTP-style overview response with mixed polymorphic
        // marker types. This is a pure wire-contract selftest, NOT an HTTP/DB test.
        var bbox = new Bbox(5.2, 51.6, 5.9, 52.1);
        var coverage = MapOverviewCoverage.FromGrid(MapGrid.Cover(bbox, 8), complete: true);
        var cluster = new MapOverviewClusterMarker(
            8, 130, 82, 5.5, 51.75, 3, 1, 1, 1);
        Require(cluster.HasValidPartition, "complete three-way location partition");
        var response = new MapOverviewResponse(
            1, 1, bbox, 6.0, 8, coverage,
            null, null, true,
            new MapOverviewMarker[] { cluster, free, noEvse });
        using (var json = JsonDocument.Parse(JsonSerializer.Serialize(response, options)))
        {
            var root = json.RootElement;
            Require(root.GetProperty("schemaVersion").GetInt32() == 1 &&
                    root.GetProperty("filterVersion").GetInt32() == 1 &&
                    root.GetProperty("coverage").GetProperty("complete").GetBoolean() &&
                    root.GetProperty("dataVersion").ValueKind == JsonValueKind.Null &&
                    root.GetProperty("feedIsStale").GetBoolean(),
                "overview response metadata, full coverage and nullable data version");
            var markers = root.GetProperty("markers").EnumerateArray().ToArray();
            Require(markers.Length == 3 &&
                    markers[0].GetProperty("kind").GetString() == "cluster" &&
                    markers[0].GetProperty("locationCount").GetInt32() == 3 &&
                    markers[0].GetProperty("locationsWithFree").GetInt32() == 1 &&
                    markers[0].GetProperty("locationsWithNoFree").GetInt32() == 1 &&
                    markers[0].GetProperty("locationsUnknown").GetInt32() == 1,
                "cluster JSON uses location counts and correct discriminator");
            Require(markers[1].GetProperty("kind").GetString() == "location" &&
                    markers[1].GetProperty("availableCount").GetInt32() == 1 &&
                    markers[1].GetProperty("totalCount").GetInt32() == 2 &&
                    markers[1].GetProperty("countQuality").GetString() == "known",
                "free location is numeric and known in complete response");
            Require(markers[2].GetProperty("kind").GetString() == "location" &&
                    markers[2].GetProperty("featureId").GetString() == "NL-ABC-zero" &&
                    markers[2].GetProperty("availableCount").GetInt32() == 0 &&
                    markers[2].GetProperty("totalCount").GetInt32() == 0 &&
                    markers[2].GetProperty("countQuality").GetString() == "unknown",
                "0/0 location remains numeric, unknown and OCPI-identified");
        }
        try
        {
            MapOverviewLocationMarker.FromCounts(
                8,130,82,5.53,51.75,"NL-ABC-invalid",2,1);
            throw new InvalidOperationException("Invalid counts accepted");
        }
        catch (InvalidDataException) { }

        Console.WriteLine("Map overview phase B compatibility selftest passed.");
    }
}
