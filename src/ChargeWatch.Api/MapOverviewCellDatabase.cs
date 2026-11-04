using Npgsql;
using NpgsqlTypes;

// Read-only, filtered cell-member lookup. Reuses MapFilter for identical
// membership semantics to /map/overview, without truncating the cell.
internal sealed record MapOverviewCellLocation(
    string FeatureId, double Longitude, double Latitude, string Address,
    string? OperatorName, int AvailableCount, int TotalCount);
internal sealed record MapOverviewCellPage(
    int GridLevel, long GridX, long GridY, int Offset, int Limit,
    int TotalCount, bool HasMore, IReadOnlyList<MapOverviewCellLocation> Items);

internal static class MapOverviewCellDatabase
{
    public static async Task<MapOverviewCellPage> ReadAsync(
        NpgsqlConnection connection, int level, long x, long y,
        int offset, int limit, MapFilter filters, CancellationToken cancellationToken)
    {
        var cell = new MapGridCell(level, x, y);
        var bounds = MapGrid.Bounds(cell); // validates level and indices
        var meters = MapGrid.WorldMeters / (1L << level);
        await using var transaction = await connection.BeginTransactionAsync(
            System.Data.IsolationLevel.RepeatableRead, cancellationToken);
        await using (var readOnly = new NpgsqlCommand("SET TRANSACTION READ ONLY", connection, transaction))
            await readOnly.ExecuteNonQueryAsync(cancellationToken);

        // The geographic bbox is conservative; exact ownership is canonical
        // Web Mercator floor() arithmetic, identical to MapOverviewSql.Build.
        var sql = $"""
            WITH selected AS MATERIALIZED (
              SELECT l.id, l.country_code, l.party_id, l.source_id,
                     l.longitude, l.latitude, l.address, l.operator_name,
                     floor((ST_X(ST_Transform(l.position::geometry,3857))
                           +20037508.342789244)/$5)::bigint AS cx,
                     floor((20037508.342789244
                           -ST_Y(ST_Transform(l.position::geometry,3857)))/$5)::bigint AS cy
              FROM charging_location l
              WHERE l.is_active AND l.position IS NOT NULL
                AND l.position && ST_MakeEnvelope($1,$2,$3,$4,4326)::geography
                {filters.LocationPredicate(10)}
            ), matching AS MATERIALIZED (
              SELECT * FROM selected WHERE cx=$6 AND cy=$7
            ), page AS (
              SELECT * FROM matching ORDER BY id LIMIT $8 OFFSET $9
            )
            SELECT p.country_code,p.party_id,p.source_id,p.longitude,p.latitude,
                   p.address,p.operator_name,
                   count(e.id) FILTER (WHERE upper(e.status)='AVAILABLE')::int AS free,
                   count(e.id) FILTER (WHERE upper(btrim(coalesce(e.status,'')))
                     NOT IN ('REMOVED','PLANNED'))::int AS total,
                   totals.location_count
            FROM (SELECT count(*)::int AS location_count FROM matching) totals
            LEFT JOIN page p ON true
            LEFT JOIN evse e ON e.location_id=p.id AND e.is_active
            GROUP BY p.id,p.country_code,p.party_id,p.source_id,p.longitude,
                     p.latitude,p.address,p.operator_name,totals.location_count
            ORDER BY p.id
            """;
        await using var cmd = new NpgsqlCommand(sql, connection, transaction) { CommandTimeout = 5 };
        cmd.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=bounds.MinLon });
        cmd.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=bounds.MinLat });
        cmd.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=bounds.MaxLon });
        cmd.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=bounds.MaxLat });
        cmd.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=meters });
        cmd.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Bigint, Value=x });
        cmd.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Bigint, Value=y });
        cmd.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Integer, Value=limit });
        cmd.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Integer, Value=offset });
        filters.AddParameters(cmd);

        var result = new List<MapOverviewCellLocation>();
        var total = 0;
        await using (var reader = await cmd.ExecuteReaderAsync(cancellationToken))
        {
            while (await reader.ReadAsync(cancellationToken))
            {
                total = reader.GetInt32(9);
                if (reader.IsDBNull(0)) continue; // offset outside final page
                result.Add(new MapOverviewCellLocation(
                    reader.GetString(0)+"-"+reader.GetString(1)+"-"+reader.GetString(2),
                    reader.GetDouble(3),reader.GetDouble(4),reader.GetString(5),
                    reader.IsDBNull(6)?null:reader.GetString(6),
                    reader.GetInt32(7),reader.GetInt32(8)));
            }
        }
        await transaction.CommitAsync(cancellationToken);
        return new MapOverviewCellPage(level,x,y,offset,limit,total,
            (long)offset + result.Count < total, result);
    }
}
