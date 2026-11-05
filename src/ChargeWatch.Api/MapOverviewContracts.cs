using System.Text.Json.Serialization;

// Phase A: typed wire contract only. No new API route is registered yet.
// A marker is either a cluster or one existing OCPI feature.
[JsonPolymorphic(TypeDiscriminatorPropertyName = "kind")]
[JsonDerivedType(typeof(MapOverviewClusterMarker), "cluster")]
[JsonDerivedType(typeof(MapOverviewLocationMarker), "location")]
internal abstract record MapOverviewMarker(int GridLevel, long GridX, long GridY,
    double Longitude, double Latitude);

internal sealed record MapOverviewClusterMarker(
    int GridLevel, long GridX, long GridY,
    double Longitude, double Latitude,
    int LocationCount,
    int LocationsWithFree,
    int LocationsWithNoFree,
    int LocationsUnknown)
    : MapOverviewMarker(GridLevel, GridX, GridY, Longitude, Latitude)
{
    public bool HasValidPartition =>
        LocationCount >= 2 && LocationsWithFree >= 0 &&
        LocationsWithNoFree >= 0 && LocationsUnknown >= 0 &&
        (long)LocationsWithFree + LocationsWithNoFree + LocationsUnknown == LocationCount;
}

internal sealed record MapOverviewLocationMarker(
    int GridLevel, long GridX, long GridY,
    double Longitude, double Latitude,
    string FeatureId, // Existing external country_code-party_id-source_id identity.
    int? AvailableCount, int? TotalCount, string CountQuality)
    : MapOverviewMarker(GridLevel, GridX, GridY, Longitude, Latitude)
{
    // The legacy ChargeWatch/Android map always has numeric counts.
    // 0/0 remains a numeric label with unknown availability, not null/?.
    public static MapOverviewLocationMarker FromCounts(
        int gridLevel, long gridX, long gridY, double longitude, double latitude,
        string featureId, int availableCount, int totalCount)
    {
        if (availableCount < 0 || totalCount < 0 || availableCount > totalCount)
            throw new InvalidDataException("Invalid map location available/total counts.");
        return new MapOverviewLocationMarker(gridLevel, gridX, gridY,
            longitude, latitude, featureId, availableCount, totalCount,
            totalCount > 0 ? "known" : "unknown");
    }
}

// A complete response does not imply fresh charging status data.
// DataVersion remains null until a *real* atomically published source version
// is available; clients must not merge snapshots solely on feed timestamps.
internal sealed record MapOverviewResponse(
    int SchemaVersion,
    int FilterVersion,
    Bbox RequestedBbox,
    double RequestedZoom,
    int GridLevel,
    MapOverviewCoverage Coverage,
    string? DataVersion,
    DateTimeOffset? FeedLastObservedAt,
    bool FeedIsStale,
    IReadOnlyList<MapOverviewMarker> Markers)
{
    // Diagnostic sum, not a substitute for independent SQL coverage checks.
    public long RepresentedLocationCount => Markers.Sum(marker =>
        marker is MapOverviewClusterMarker cluster ? (long)cluster.LocationCount : 1L);
}

internal sealed record MapOverviewCoverage(
    bool Complete,
    int GridLevel,
    long MinCellX,
    long MinCellY,
    long MaxCellX,
    long MaxCellY)
{
    public static MapOverviewCoverage FromGrid(MapGridCoverage grid, bool complete) =>
        new(complete, grid.GridLevel, grid.MinCellX, grid.MinCellY,
            grid.MaxCellX, grid.MaxCellY);
}
