internal sealed record ApiOptions(string ConnectionString, TimeSpan StaleAfter)
{
    public static ApiOptions FromEnvironment()
    {
        var connection = Environment.GetEnvironmentVariable("CW_API_CONNECTION");
        if (string.IsNullOrWhiteSpace(connection))
            throw new InvalidOperationException("CW_API_CONNECTION must use the chargewatch_read PostgreSQL role.");

        var staleMinutes = 15;
        var raw = Environment.GetEnvironmentVariable("CW_API_STALE_AFTER_MINUTES");
        if (!string.IsNullOrWhiteSpace(raw) && (!int.TryParse(raw, out staleMinutes) || staleMinutes < 1 || staleMinutes > 1440))
            throw new InvalidOperationException("CW_API_STALE_AFTER_MINUTES must be between 1 and 1440.");

        return new ApiOptions(connection, TimeSpan.FromMinutes(staleMinutes));
    }

    public static void SelfTest()
    {
        static void Require(bool condition, string message)
        {
            if (!condition) throw new InvalidOperationException("API selftest failed: " + message);
        }

        Require(ParseLimit(null) == 100, "default limit");
        Require(ParseLimit(1) == 1, "minimum limit");
        Require(ParseLimit(999) == 200, "maximum limit");
        Require(TryParseBbox("4.8,51.7,5.0,51.9", out var bbox) && bbox.MinLon < bbox.MaxLon, "bbox parsing");
        Require(!TryParseBbox("5,52,4,51", out _), "invalid bbox order");
        var sample = new LocationListItem(
            1, "NL", "ABC", "source", "Teststraat", "Oss", null, 51.75, 5.53,
            2, 0, 2, 0, 0,
            DateTimeOffset.UtcNow.AddHours(-1), true,
            DateTimeOffset.UtcNow.AddMinutes(-1), false, null);
        var json = System.Text.Json.JsonSerializer.Serialize(sample,
            new System.Text.Json.JsonSerializerOptions(System.Text.Json.JsonSerializerDefaults.Web));
        using var parsed = System.Text.Json.JsonDocument.Parse(json);
        var root = parsed.RootElement;
        Require(root.GetProperty("isStale").GetBoolean() &&
                !root.GetProperty("feedIsStale").GetBoolean() &&
                root.GetProperty("feedLastObservedAt").ValueKind == System.Text.Json.JsonValueKind.String,
            "separate source-age and feed freshness fields in Android JSON contract");
        Console.WriteLine("ChargeWatch API selftest passed.");
    }

    public static int ParseLimit(int? limit) => Math.Clamp(limit ?? 100, 1, 200);

    public static bool TryParseBbox(string? value, out Bbox bbox)
    {
        bbox = default;
        if (string.IsNullOrWhiteSpace(value)) return false;
        var parts = value.Split(',', StringSplitOptions.TrimEntries);
        if (parts.Length != 4) return false;
        if (!double.TryParse(parts[0], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var minLon) ||
            !double.TryParse(parts[1], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var minLat) ||
            !double.TryParse(parts[2], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var maxLon) ||
            !double.TryParse(parts[3], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var maxLat))
            return false;
        if (minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90 || minLon >= maxLon || minLat >= maxLat)
            return false;
        bbox = new Bbox(minLon, minLat, maxLon, maxLat);
        return true;
    }
}

internal readonly record struct Bbox(double MinLon, double MinLat, double MaxLon, double MaxLat);
