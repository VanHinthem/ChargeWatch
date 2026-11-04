// Pure canonical Web Mercator grid calculations for ChargeWatch map overview.
// No database access or endpoint behavior changes in phase A.
internal static class MapGrid
{
    public const int MinLevel = 0;
    public const int MaxLevel = 22;
    public const int InitialMarkerBudget = 500; // Provisional; measure on Android.
    public const double WorldHalfMeters = 20037508.342789244;
    public const double WorldMeters = 40075016.68557849;
    public const double MaxMercatorLatitude = 85.0511287798066;
    private const double EarthRadiusMeters = 6378137.0;

    public static MapGridCell CellAt(double longitude, double latitude, int level)
    {
        ValidateLevel(level);
        ValidatePosition(longitude, latitude);
        var cells = 1L << level;
        var xMeters = EarthRadiusMeters * longitude * Math.PI / 180.0;
        var radians = latitude * Math.PI / 180.0;
        var yMeters = EarthRadiusMeters * Math.Log(Math.Tan(Math.PI / 4.0 + radians / 2.0));
        var size = WorldMeters / cells;
        // Canonical half-open cells, consistent with PostgreSQL floor() ownership.
        return new MapGridCell(level,
            ClampIndex((long)Math.Floor((xMeters + WorldHalfMeters) / size), cells),
            ClampIndex((long)Math.Floor((WorldHalfMeters - yMeters) / size), cells));
    }

    public static MapGridCoverage Cover(Bbox bbox, int level, int bufferCells = 0)
    {
        ValidateLevel(level);
        if (bufferCells < 0 || bufferCells > 8)
            throw new ArgumentOutOfRangeException(nameof(bufferCells));
        if (!double.IsFinite(bbox.MinLon) || !double.IsFinite(bbox.MaxLon) ||
            !double.IsFinite(bbox.MinLat) || !double.IsFinite(bbox.MaxLat) ||
            bbox.MinLon < -180 || bbox.MaxLon > 180 ||
            bbox.MinLat < -MaxMercatorLatitude || bbox.MaxLat > MaxMercatorLatitude ||
            bbox.MinLon >= bbox.MaxLon || bbox.MinLat >= bbox.MaxLat)
            throw new ArgumentOutOfRangeException(nameof(bbox), "Invalid non-wrapping Web Mercator bbox.");

        // This deliberately includes the max-boundary cell, matching the
        // existing SQL bounds formula floor(maxCoordinate/cellSize).
        var northWest = CellAt(bbox.MinLon, bbox.MaxLat, level);
        var southEast = CellAt(bbox.MaxLon, bbox.MinLat, level);
        var max = (1L << level) - 1;
        return new MapGridCoverage(level,
            Math.Max(0, northWest.X - bufferCells),
            Math.Max(0, northWest.Y - bufferCells),
            Math.Min(max, southEast.X + bufferCells),
            Math.Min(max, southEast.Y + bufferCells));
    }

    public static Bbox Bounds(MapGridCell cell)
    {
        ValidateLevel(cell.Level);
        var cells = 1L << cell.Level;
        if (cell.X < 0 || cell.Y < 0 || cell.X >= cells || cell.Y >= cells)
            throw new ArgumentOutOfRangeException(nameof(cell));
        var size = WorldMeters / cells;
        var west = (-WorldHalfMeters + cell.X * size) / EarthRadiusMeters * 180.0 / Math.PI;
        var east = (-WorldHalfMeters + (cell.X + 1) * size) / EarthRadiusMeters * 180.0 / Math.PI;
        var north = InverseMercatorLatitude(WorldHalfMeters - cell.Y * size);
        var south = InverseMercatorLatitude(WorldHalfMeters - (cell.Y + 1) * size);
        return new Bbox(Math.Clamp(west, -180, 180), south,
                        Math.Clamp(east, -180, 180), north);
    }

    public static (double Longitude, double Latitude) Center(MapGridCell cell)
    {
        var bounds = Bounds(cell);
        // Average in projected Y, rather than averaging WGS84 latitudes.
        var size = WorldMeters / (1L << cell.Level);
        return ((bounds.MinLon + bounds.MaxLon) / 2.0,
            InverseMercatorLatitude(WorldHalfMeters - (cell.Y + 0.5) * size));
    }

    private static double InverseMercatorLatitude(double yMeters) =>
        (2.0 * Math.Atan(Math.Exp(yMeters / EarthRadiusMeters)) - Math.PI / 2.0) * 180.0 / Math.PI;

    private static void ValidateLevel(int level)
    {
        if (level < MinLevel || level > MaxLevel)
            throw new ArgumentOutOfRangeException(nameof(level));
    }

    private static void ValidatePosition(double longitude, double latitude)
    {
        if (!double.IsFinite(longitude) || !double.IsFinite(latitude) ||
            longitude < -180 || longitude > 180 ||
            latitude < -MaxMercatorLatitude || latitude > MaxMercatorLatitude)
            throw new ArgumentOutOfRangeException(nameof(longitude), "Position outside Web Mercator domain.");
    }

    private static long ClampIndex(long index, long count) => Math.Clamp(index, 0, count - 1);
}

internal readonly record struct MapGridCell(int Level, long X, long Y);

internal readonly record struct MapGridCoverage(
    int GridLevel, long MinCellX, long MinCellY, long MaxCellX, long MaxCellY)
{
    public bool Contains(MapGridCell cell) =>
        cell.Level == GridLevel &&
        cell.X >= MinCellX && cell.X <= MaxCellX &&
        cell.Y >= MinCellY && cell.Y <= MaxCellY;
    public long CellCount => checked((MaxCellX - MinCellX + 1) * (MaxCellY - MinCellY + 1));
}
