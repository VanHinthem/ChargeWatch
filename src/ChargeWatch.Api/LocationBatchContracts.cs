using System.Text.Json;

internal sealed class LocationBatchRequest
{
    public string[]? FeatureIds { get; init; }
    // True by default so one batch can supply the complete expanded-sheet
    // contract. Clients may omit historical statistics for frequent polls.
    public bool IncludeStatistics { get; init; } = true;
}

internal sealed record LocationBatchEntry(
    string FeatureId, string Status, LocationDetail? Detail, LocationStatistics? Statistics);

internal sealed record LocationBatchResponse(IReadOnlyList<LocationBatchEntry> Locations);

internal static class LocationBatchContracts
{
    public const int MaxLocations = 20;

    public static bool TryValidate(
        LocationBatchRequest? request,
        out IReadOnlyList<(string FeatureId, NdwLocationKey Key)> parsed,
        out string? error)
    {
        parsed = [];
        error = null;
        if (request?.FeatureIds is null ||
            request.FeatureIds.Length is < 1 or > MaxLocations)
        {
            error = "featureIds must contain between 1 and 20 NDW feature IDs.";
            return false;
        }

        var seen = new HashSet<string>(StringComparer.Ordinal);
        var values = new List<(string FeatureId, NdwLocationKey Key)>();
        foreach (var id in request.FeatureIds)
        {
            if (!NdwLocationKey.TryParse(id, out var key))
            {
                error = "featureIds contains an invalid NDW feature ID.";
                return false;
            }
            if (!seen.Add(id))
            {
                error = "featureIds must not contain duplicates.";
                return false;
            }
            values.Add((id, key));
        }
        parsed = values;
        return true;
    }

    public static void SelfTest()
    {
        static void Check(bool condition, string description)
        {
            if (!condition) throw new InvalidOperationException("Batch API selftest: " + description);
        }
        var sample = new LocationBatchRequest { FeatureIds = ["NL-ABC-123", "NL-DEF-with-hyphen"] };
        Check(TryValidate(sample, out var keys, out _) && keys.Count == 2 &&
              keys[1].Key.SourceId == "with-hyphen", "valid ordered IDs");
        Check(!TryValidate(new LocationBatchRequest { FeatureIds = [] }, out _, out _), "no empty request");
        Check(!TryValidate(new LocationBatchRequest {
            FeatureIds = Enumerable.Repeat("NL-ABC-123", 21).ToArray()
        }, out _, out _), "max 20");
        Check(!TryValidate(new LocationBatchRequest {
            FeatureIds = ["NL-ABC-123", "NL-ABC-123"]
        }, out _, out _), "duplicate");
        Check(!TryValidate(new LocationBatchRequest {
            FeatureIds = ["NL-ABC-123", "not an ID"]
        }, out _, out _), "malformed ID");
        using var omitted = JsonDocument.Parse(
            JsonSerializer.Serialize(sample, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
        Check(omitted.RootElement.GetProperty("includeStatistics").GetBoolean(), "default full data");
        var response = new LocationBatchResponse([
            new LocationBatchEntry("NL-ABC-123", "NOT_FOUND", null, null)
        ]);
        using var json = JsonDocument.Parse(JsonSerializer.Serialize(response,
            new JsonSerializerOptions(JsonSerializerDefaults.Web)));
        Check(json.RootElement.GetProperty("locations")[0].GetProperty("status").GetString() == "NOT_FOUND",
            "not-found response format");
    }
}
