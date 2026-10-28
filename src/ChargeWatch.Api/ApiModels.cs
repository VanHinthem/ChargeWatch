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
internal sealed record ConnectorTariffOption(
    string CountryCode, string PartyId, string SourceId,
    string Status, string? Currency, DateTimeOffset? SourceUpdatedAt,
    IReadOnlyList<EvaluatedTariffComponent> Components,
    IReadOnlyList<string> Limitations);

internal static class ConnectorTariffContract
{
    public const string EvaluationZone = "Europe/Amsterdam"; // Netherlands-only product.
    public static ConnectorTariffOption Missing(string country, string party, string id)
        => new(country, party, id, "SOURCE_MISSING", null, null, [], ["SOURCE_TARIFF_NOT_FOUND"]);

    public static ConnectorTariffOption Evaluate(string country, string party, string id,
        string? currency, JsonElement details, DateTimeOffset? sourceUpdatedAt, DateTimeOffset asOfUtc)
    {
        var metadata = OcpiTariffMetadataParser.Parse(details);
        var evaluation = OcpiTariffEvaluator.Evaluate(details, asOfUtc, EvaluationZone);
        var limitations = evaluation.Limitations.Concat(metadata.Limitations).Distinct().OrderBy(x => x).ToArray();
        var status = evaluation.Components.Count == 0 ? "UNAVAILABLE" :
            evaluation.Components.All(x => x.Certainty == TariffCertainty.Applicable) && limitations.Length == 0
                ? "APPLICABLE" : "CONDITIONAL";
        return new(country, party, id, status, metadata.Currency ?? currency, sourceUpdatedAt,
            evaluation.Components, limitations);
    }

    public static void SelfTest()
    {
        using var doc = JsonDocument.Parse("""
            {"currency":"EUR","elements":[{"price_components":[
                {"type":"ENERGY","price":0.42,"step_size":1,"vat":null}]}]}
            """);
        var option = Evaluate("NL","EFL","demo","EUR",doc.RootElement,null,
            DateTimeOffset.Parse("2026-09-03T10:30:00Z"));
        if (option.Status != "APPLICABLE" || option.Components.Count != 1 ||
            option.Components[0].Price != 0.42m ||
            Missing("NL","UB2","missing").Status != "SOURCE_MISSING" ||
            new ConnectorDetail { Id=1,SourceId="x" }.TariffStatus != "NO_REFERENCE")
            throw new InvalidOperationException("Connector tariff contract self-test failed.");
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
    long TariffCount);

internal sealed record MapConnectorCategory(
    int Available, int Total, string? ConnectorType, string? PowerType, int? PowerMax,
    long? EffectivePowerWatts, string PowerSource);
internal sealed record MapLocationItem(
    string FeatureId, double Longitude, double Latitude, string Address,
    string? OperatorName, DateTimeOffset? LastUpdated,
    int AvailableCount, int TotalCount,
    IReadOnlyList<MapConnectorCategory> ConnectorCategories);
internal sealed record MapViewportResult(IReadOnlyList<MapLocationItem> Locations, bool Truncated);
