using System.Text.Json;

internal sealed record LocationListItem(
    long Id,
    string CountryCode,
    string PartyId,
    string SourceId,
    string Address,
    string City,
    string? OperatorName,
    double? Latitude,
    double? Longitude,
    int EvseCount,
    int AvailableCount,
    int OccupiedCount,
    int FaultCount,
    int UnknownCount,
    DateTimeOffset? SourceUpdatedAt,
    bool IsStale,
    DateTimeOffset? FeedLastObservedAt,
    bool FeedIsStale,
    double? DistanceMeters);

internal sealed class LocationDetail
{
    public required long Id { get; init; }
    public required string CountryCode { get; init; }
    public required string PartyId { get; init; }
    public required string SourceId { get; init; }
    public required string Address { get; init; }
    public required string City { get; init; }
    public string? OperatorName { get; init; }
    public double? Latitude { get; init; }
    public double? Longitude { get; init; }
    public DateTimeOffset? SourceUpdatedAt { get; init; }
    public required bool IsStale { get; init; }
    public DateTimeOffset? FeedLastObservedAt { get; init; }
    public bool FeedIsStale { get; init; }
    public DateTimeOffset? TariffsFeedLastCheckedAt { get; init; }
    public bool TariffsFeedIsStale { get; init; }
    public List<EvseDetail> Evses { get; } = [];
}

internal sealed class EvseDetail
{
    public required long Id { get; init; }
    public required string SourceUid { get; init; }
    public string? ExternalEvseId { get; init; }
    public required string Status { get; init; }
    public DateTimeOffset? SourceUpdatedAt { get; init; }
    public List<ConnectorDetail> Connectors { get; } = [];
}

internal sealed class ConnectorDetail
{
    public required long Id { get; init; }
    public required string SourceId { get; init; }
    public string? Standard { get; init; }
    public string? PowerType { get; init; }
    public int? MaxElectricPower { get; init; }
    public string? Format { get; init; }
    public int? MaxVoltage { get; init; }
    public int? MaxAmperage { get; init; }
    public long? EffectivePowerWatts => ConnectorPower.Estimate(PowerType, MaxElectricPower, MaxVoltage, MaxAmperage).Watts;
    public string PowerSource => ConnectorPower.Estimate(PowerType, MaxElectricPower, MaxVoltage, MaxAmperage).Source;
    public List<ConnectorTariffOption> TariffOptions { get; } = [];
    public string TariffStatus => TariffOptions.Count == 0 ? "NO_REFERENCE" :
        TariffOptions.Any(x => x.Status == "SOURCE_MISSING") ? "SOURCE_MISSING" :
        TariffOptions.Count > 1 ? "MULTIPLE_OPTIONS" : TariffOptions[0].Status;
}

// One option per actual OCPI connector.tariff_ids reference. Never silently drop missing IDs.
internal sealed record TariffComponentDto(
    string Dimension, decimal Price, int? StepSize, decimal? VatPercent,
    int ElementIndex, string Certainty);

internal sealed record ConnectorTariffOption(
    string CountryCode, string PartyId, string SourceId,
    string Status, string? Currency, DateTimeOffset? SourceUpdatedAt,
    DateTimeOffset? AsOfUtc, IReadOnlyList<TariffComponentDto> Components,
    IReadOnlyList<string> Limitations,
    decimal? MinimumPrice = null, decimal? MaximumPrice = null,
    string? AlternativeUrl = null, bool HasAlternativeText = false);

internal static class ConnectorTariffContract
{
    public const string EvaluationZone = "Europe/Amsterdam"; // Netherlands-only product.
    public static ConnectorTariffOption Missing(string country, string party, string id)
        => new(country, party, id, "SOURCE_MISSING", null, null, null, [], ["SOURCE_TARIFF_NOT_FOUND"]);

    public static ConnectorTariffOption Evaluate(string country, string party, string id,
        string? currency, JsonElement details, DateTimeOffset? sourceUpdatedAt, DateTimeOffset asOfUtc)
    {
        var metadata = OcpiTariffMetadataParser.Parse(details);
        var evaluation = OcpiTariffEvaluator.Evaluate(details, asOfUtc, EvaluationZone);
        var limitations = evaluation.Limitations.Concat(metadata.Limitations).Distinct().OrderBy(x => x).ToArray();
        var status = evaluation.Components.Count == 0 && limitations.Any(x => x.StartsWith("INVALID_", StringComparison.Ordinal)) ? "MALFORMED" :
            evaluation.Components.Count == 0 ? "UNAVAILABLE" :
            evaluation.Components.All(x => x.Certainty == TariffCertainty.Applicable) && limitations.Length == 0
                ? "APPLICABLE" : "CONDITIONAL";
        return new(country, party, id, status, metadata.Currency ?? currency, sourceUpdatedAt,
            asOfUtc, evaluation.Components.Select(x => new TariffComponentDto(x.Dimension, x.Price,
                x.StepSize, x.VatPercent, x.ElementIndex, x.Certainty.ToString())).ToArray(), limitations,
            metadata.MinimumPrice, metadata.MaximumPrice, metadata.AlternativeUrl, metadata.HasAlternativeText);
    }

    public static void SelfTest()
    {
        static void Require(bool condition, string message)
        {
            if (!condition) throw new InvalidOperationException("Connector tariff self-test: " + message);
        }

        var empty = new ConnectorDetail { Id = 1, SourceId = "none" };
        Require(empty.TariffStatus == "NO_REFERENCE" && empty.TariffOptions.Count == 0,
            "missing reference must not invent a price");

        var missing = Missing("NL", "UB2", "absent-in-ndw");
        var unresolved = new ConnectorDetail { Id = 2, SourceId = "unresolved" };
        unresolved.TariffOptions.Add(missing);
        Require(unresolved.TariffStatus == "SOURCE_MISSING" &&
                missing.CountryCode == "NL" && missing.PartyId == "UB2" &&
                missing.SourceId == "absent-in-ndw" && missing.Components.Count == 0 &&
                missing.AsOfUtc is null &&
                missing.Limitations.Contains("SOURCE_TARIFF_NOT_FOUND"),
            "unresolved OCPI reference must preserve its source key without a price");

        using var doc = JsonDocument.Parse("""
            {"currency":"EUR","elements":[{"price_components":[
                {"type":"ENERGY","price":0.42,"step_size":1,"vat":null}]}]}
            """);
        var now = DateTimeOffset.Parse("2026-09-03T10:30:00Z");
        var first = Evaluate("NL", "EFL", "rate-A", "EUR", doc.RootElement, null, now);
        var second = Evaluate("NL", "EFL", "rate-B", "EUR", doc.RootElement, null, now);
        Require(first.Status == "APPLICABLE" && first.Components.Count == 1 &&
                first.Components[0].Price == 0.42m &&
                first.Components[0].Certainty == "Applicable" && first.AsOfUtc == now,
            "single evaluated component must have explicit certainty and timestamp");

        var multiple = new ConnectorDetail { Id = 3, SourceId = "two-rates" };
        multiple.TariffOptions.Add(first);
        multiple.TariffOptions.Add(second);
        Require(multiple.TariffStatus == "MULTIPLE_OPTIONS" &&
                multiple.TariffOptions.Count == 2 &&
                multiple.TariffOptions[0].SourceId == "rate-A" &&
                multiple.TariffOptions[1].SourceId == "rate-B",
            "multiple distinct tariff options must remain separate");

        multiple.TariffOptions.Add(missing);
        Require(multiple.TariffStatus == "SOURCE_MISSING" && multiple.TariffOptions.Count == 3,
            "partially unresolved references must not be presented as complete");

        using var malformedJson = JsonDocument.Parse("""{"currency":"EUR","elements":"broken"}""");
        var malformed = Evaluate("NL", "EFL", "invalid", "EUR", malformedJson.RootElement, null, now);
        Require(malformed.Status == "MALFORMED" && malformed.Components.Count == 0 &&
                malformed.Limitations.Contains("INVALID_ELEMENTS"),
            "malformed source tariff must be distinct from missing source and unavailable time slot");

        using var boundedJson = JsonDocument.Parse("""
            {"currency":"EUR","min_price":{"excl_vat":0.25},"max_price":{"excl_vat":50.0},
             "tariff_alt_url":"https://example.org/tariff",
             "tariff_alt_text":[{"language":"en","text":"Source terms"}],
             "elements":[{"price_components":[{"type":"ENERGY","price":0.42,"step_size":1}]}]}
            """);
        var bounded = Evaluate("NL","EFL","bounded",null,boundedJson.RootElement,null,now);
        Require(bounded.MinimumPrice == 0.25m && bounded.MaximumPrice == 50m &&
                bounded.AlternativeUrl == "https://example.org/tariff" && bounded.HasAlternativeText,
            "OCPI price bounds and source terms must remain visible in API model");

        var json = JsonSerializer.Serialize(multiple,
            new JsonSerializerOptions(JsonSerializerDefaults.Web));
        using var parsed = JsonDocument.Parse(json);
        var root = parsed.RootElement;
        Require(root.GetProperty("tariffStatus").GetString() == "SOURCE_MISSING" &&
                root.GetProperty("tariffOptions").GetArrayLength() == 3 &&
                root.GetProperty("tariffOptions")[2].GetProperty("components").GetArrayLength() == 0,
            "JSON contract must expose missing source and preserve all options");
    }
}

internal sealed record AvailabilitySlot(
    int IsoWeekday,
    int Hour,
    double? AvailableProbability,
    int SampleCount,
    int ChangedFeedCount,
    int UnchangedFeedCount,
    DateTimeOffset? LastSampleAt,
    double? AverageAvailableEvses = null,
    double? AverageActiveEvses = null,
    int? AvailableEvseSampleCount = null,
    int? ExcludedEvseSampleCount = null);

internal sealed record EvseStatistics(long EvseId, IReadOnlyList<AvailabilitySlot> Slots);

internal sealed record LocationStatistics(
    long LocationId,
    IReadOnlyList<AvailabilitySlot> Slots,
    IReadOnlyList<EvseStatistics> Evses)
{
    public bool IsExperimental => true;
    public string ProbabilityInterpretation => "UNVALIDATED_SNAPSHOT_EWMA";
}

internal sealed record DataStatus(
    DateTimeOffset? LastSuccessfulImportAt,
    DateTimeOffset? LastObservedPollAt,
    double? AgeSeconds,
    bool IsStale,
    int StaleAfterMinutes,
    long LocationCount,
    long EvseCount,
    long ConnectorCount,
    long TariffCount,
    DateTimeOffset? TariffsFeedLastCheckedAt,
    bool TariffsFeedIsStale);

internal sealed record MapConnectorCategory(
    int Available, int Total, string? ConnectorType, string? PowerType, int? PowerMax,
    long? EffectivePowerWatts, string PowerSource);
internal sealed record MapLocationItem(
    string FeatureId, double Longitude, double Latitude, string Address,
    string? OperatorName, DateTimeOffset? LastUpdated,
    int AvailableCount, int TotalCount,
    IReadOnlyList<MapConnectorCategory> ConnectorCategories);
internal sealed record MapViewportResult(IReadOnlyList<MapLocationItem> Locations, bool Truncated);
