using Npgsql;
using NpgsqlTypes;

// Phase B: read-only, complete-cell overview on one PostgreSQL snapshot.
// Uses the production MapFilter and no raw-location LIMIT. The rollout remains
// behind CW_ENABLE_MAP_OVERVIEW until phase C/D validation.
internal static class MapOverviewDatabase
{
    public static async Task<MapOverviewResponse> ReadAsync(
        NpgsqlConnection connection, Bbox bbox, double zoom, MapFilter filters,
        TimeSpan staleAfter,
        CancellationToken cancellationToken)
    {
        if (!double.IsFinite(zoom) || zoom < 0 || zoom > 22)
            throw new ArgumentOutOfRangeException(nameof(zoom));
        // Provisional zoom mapping. Phase C will tune object budgeting and
        // effective grid resolution; do not promise a production zoom policy.
        var gridLevel = Math.Clamp((int)Math.Floor(zoom) + 2, MapGrid.MinLevel, MapGrid.MaxLevel);
        var grid = MapGrid.Cover(bbox, gridLevel);
        // SQL uses the same meter origin/cell length as MapGrid and P2i/P2l.
        var cellMeters = MapGrid.WorldMeters / (1L << gridLevel);
        var southwest = MapGrid.Bounds(new MapGridCell(gridLevel, grid.MinCellX, grid.MaxCellY));
        var northeast = MapGrid.Bounds(new MapGridCell(gridLevel, grid.MaxCellX, grid.MinCellY));

        await using var transaction = await connection.BeginTransactionAsync(
            System.Data.IsolationLevel.RepeatableRead, cancellationToken);
        await using (var readOnly = new NpgsqlCommand("SET TRANSACTION READ ONLY",connection,transaction))
            await readOnly.ExecuteNonQueryAsync(cancellationToken);

        DateTimeOffset? observed;
        await using (var feed = new NpgsqlCommand(
            "SELECT max(last_observed_at) FROM snapshot_poll_checkpoint", connection, transaction))
        {
            var result = await feed.ExecuteScalarAsync(cancellationToken);
            observed = result is null or DBNull ? null : new DateTimeOffset((DateTime)result,TimeSpan.Zero);
        }

        // Per_location is the only place where EVSE rows are counted.
        // No connectors joined in the aggregator, so EVSEs are never duplicated.
        // Filters still use the *original* correlated MapFilter predicate.
        var sql = MapOverviewSql.Build(filters);

        await using var command = new NpgsqlCommand(sql,connection,transaction);
        command.CommandTimeout = 5;
        command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=southwest.MinLon });
        command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=southwest.MinLat });
        command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=northeast.MaxLon });
        command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=northeast.MaxLat });
        command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=cellMeters });
        filters.AddParameters(command);

        var markers = new List<MapOverviewMarker>();
        await using (var reader = await command.ExecuteReaderAsync(cancellationToken))
        {
            while (await reader.ReadAsync(cancellationToken))
            {
                // Phase B does NOT perform dynamic coarsening yet; a budget
                // overflow is an explicit error, never a partial complete map.
                if (markers.Count == MapGrid.InitialMarkerBudget)
                    throw new MapOverviewBudgetException();
                var cx=reader.GetInt64(0);
                var cy=reader.GetInt64(1);
                var count=reader.GetInt32(2);
                var green=reader.GetInt32(3);
                var red=reader.GetInt32(4);
                var unknown=reader.GetInt32(5);
                if (count < 1 || green+red+unknown != count ||
                    !grid.Contains(new MapGridCell(gridLevel,cx,cy)))
                    throw new InvalidDataException("Incomplete or invalid map overview cell partition.");

                if (count > 1)
                {
                    var center=MapGrid.Center(new MapGridCell(gridLevel,cx,cy));
                    markers.Add(new MapOverviewClusterMarker(gridLevel,cx,cy,
                        center.Longitude,center.Latitude,count,green,red,unknown));
                }
                else
                {
                    // Preserve the legacy OCPI string feature ID. Never
                    // substitute the numeric charging_location primary key.
                    var featureId = reader.GetString(6)+"-"+reader.GetString(7)+"-"+reader.GetString(8);
                    // Preserve legacy Android's numeric 0/0 for a location
                    // with no operational EVSEs; its color remains unknown.
                    markers.Add(MapOverviewLocationMarker.FromCounts(
                        gridLevel,cx,cy,reader.GetDouble(9),reader.GetDouble(10),
                        featureId,reader.GetInt32(12),reader.GetInt32(11)));
                }
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new MapOverviewResponse(1,1,bbox,zoom,gridLevel,
            MapOverviewCoverage.FromGrid(grid,true),null,observed,
            observed is null || DateTimeOffset.UtcNow-observed.Value > staleAfter,
            markers);
    }
}

internal sealed class MapOverviewBudgetException : Exception
{
    public MapOverviewBudgetException() : base("Map overview exceeds the provisional 500-marker response budget.") { }
}
