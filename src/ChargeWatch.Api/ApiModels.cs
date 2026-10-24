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
    public List<TariffDetail> Tariffs { get; } = [];
}

internal sealed record TariffDetail(
    string CountryCode,
    string PartyId,
    string SourceId,
    string? Currency,
    JsonElement Details,
    DateTimeOffset? SourceUpdatedAt);

internal sealed record AvailabilitySlot(
    int IsoWeekday,
    int Hour,
    double? AvailableProbability,
    int SampleCount,
    int ChangedFeedCount,
    int UnchangedFeedCount,
    DateTimeOffset? LastSampleAt);

internal sealed record EvseStatistics(long EvseId, IReadOnlyList<AvailabilitySlot> Slots);

internal sealed record LocationStatistics(
    long LocationId,
    IReadOnlyList<AvailabilitySlot> Slots,
    IReadOnlyList<EvseStatistics> Evses);

internal sealed record DataStatus(
    DateTimeOffset? LastSuccessfulImportAt,
    double? AgeSeconds,
    bool IsStale,
    int StaleAfterMinutes,
    long LocationCount,
    long EvseCount,
    long ConnectorCount,
    long TariffCount);
