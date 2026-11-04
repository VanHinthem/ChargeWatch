// Choose the finest complete canonical grid whose WORST-CASE cell footprint fits
// the response marker budget. No database probes or per-location truncation.
internal static class MapOverviewGridSelector
{
    internal readonly record struct Selection(int Level, MapGridCoverage Coverage);

    public static Selection Select(Bbox bbox, double zoom,
        int markerBudget = MapGrid.InitialMarkerBudget, int bufferCells = 0)
    {
        if (!double.IsFinite(zoom) || zoom < 0 || zoom > 22)
            throw new ArgumentOutOfRangeException(nameof(zoom));
        if (markerBudget < 1)
            throw new ArgumentOutOfRangeException(nameof(markerBudget));
        // Validates the bbox and optional buffer independently of selected zoom.
        MapGrid.Cover(bbox, 0, bufferCells);

        var widthMeters = MapGrid.WorldMeters * (bbox.MaxLon - bbox.MinLon) / 360.0;
        var heightMeters = MapGrid.MercatorNorthingMeters(bbox.MaxLat)
                         - MapGrid.MercatorNorthingMeters(bbox.MinLat);
        var initial = Math.Clamp((int)Math.Floor(zoom) + 2,
            MapGrid.MinLevel, MapGrid.MaxLevel);

        for (var level = initial; level >= MapGrid.MinLevel; level--)
        {
            var worldCells = 1L << level;
            var size = MapGrid.WorldMeters / worldCells;
            // +1 includes the possible partly covered cell at the far edge.
            // Independent of camera offset: a tiny pan does not change level.
            var columns = Math.Min(worldCells,
                (long)Math.Ceiling(widthMeters / size) + 1 + 2L * bufferCells);
            var rows = Math.Min(worldCells,
                (long)Math.Ceiling(heightMeters / size) + 1 + 2L * bufferCells);
            if (columns * rows > markerBudget)
                continue;

            var coverage = MapGrid.Cover(bbox, level, bufferCells);
            // A secondary exact guard protects against rounding at grid edges.
            if (coverage.CellCount <= markerBudget)
                return new Selection(level, coverage);
        }

        // Level 0 always has at most one world cell for valid input.
        throw new InvalidOperationException("No complete map grid fits the marker budget.");
    }
}
