using System.Text.Json;
using Npgsql;
using NpgsqlTypes;

internal sealed class ApiDatabase(ApiOptions options)
{
    private readonly string _connectionString = options.ConnectionString;
    private readonly TimeSpan _staleAfter = options.StaleAfter;

    public async Task AssertReadOnlyRoleAsync()
    {
        await using var connection = new NpgsqlConnection(_connectionString);
        await connection.OpenAsync();
        await using var command = new NpgsqlCommand("""
            SELECT current_user,
                   has_table_privilege(current_user,'charging_location','SELECT'),
                   has_table_privilege(current_user,'charging_location','INSERT')
                   OR has_table_privilege(current_user,'charging_location','UPDATE')
                   OR has_table_privilege(current_user,'charging_location','DELETE')
            """, connection);
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync())
            throw new InvalidOperationException("Could not validate database role.");

        var role = reader.GetString(0);
        var canRead = reader.GetBoolean(1);
        var canWrite = reader.GetBoolean(2);
        if (!string.Equals(role, "chargewatch_read", StringComparison.Ordinal) || !canRead || canWrite)
            throw new InvalidOperationException(
                $"API requires current_user=chargewatch_read with SELECT-only privileges; current_user={role}, canRead={canRead}, canWrite={canWrite}.");
    }

    public async Task<IReadOnlyList<LocationListItem>> SearchLocations(
        string? query, Bbox? bbox, double? latitude, double? longitude, int? radiusMeters, int limit)
    {
        if (latitude is not null && (latitude < -90 || latitude > 90))
            throw new ArgumentOutOfRangeException(nameof(latitude));
        if (longitude is not null && (longitude < -180 || longitude > 180))
            throw new ArgumentOutOfRangeException(nameof(longitude));
        if ((latitude is null) != (longitude is null))
            throw new ArgumentException("latitude and longitude must be supplied together.");
        if (radiusMeters is not null && (radiusMeters < 100 || radiusMeters > 100000))
            throw new ArgumentOutOfRangeException(nameof(radiusMeters), "radiusMeters must be between 100 and 100000.");

        var useNear = latitude is not null;
        int? effectiveRadius = useNear ? radiusMeters ?? 5000 : null;
        await using var connection = new NpgsqlConnection(_connectionString);
        await connection.OpenAsync();

        await using var command = new NpgsqlCommand("""
            SELECT l.id,l.country_code,l.party_id,l.source_id,l.address,l.city,l.operator_name,
                   l.latitude,l.longitude,
                   count(e.id)::int AS evse_count,
                   count(e.id) FILTER (WHERE upper(e.status)='AVAILABLE')::int AS available_count,
                   count(e.id) FILTER (WHERE upper(e.status) IN ('CHARGING','BLOCKED','RESERVED'))::int AS occupied_count,
                   count(e.id) FILTER (WHERE upper(e.status) IN ('INOPERATIVE','OUTOFORDER'))::int AS fault_count,
                   count(e.id) FILTER (WHERE e.id IS NOT NULL AND upper(e.status) NOT IN
                       ('AVAILABLE','CHARGING','BLOCKED','RESERVED','INOPERATIVE','OUTOFORDER','PLANNED','REMOVED'))::int AS unknown_count,
                   GREATEST(l.source_last_updated,max(e.source_last_updated)) AS source_updated_at,
                   CASE WHEN $7::double precision IS NULL OR l.position IS NULL THEN NULL
                        ELSE ST_Distance(l.position,ST_SetSRID(ST_MakePoint($8,$7),4326)::geography) END AS distance_meters
            FROM charging_location l
            LEFT JOIN evse e ON e.location_id=l.id
            WHERE ($1::text IS NULL OR l.address ILIKE '%'||$1||'%' OR l.city ILIKE '%'||$1||'%'
                   OR coalesce(l.operator_name,'') ILIKE '%'||$1||'%')
              AND ($2::double precision IS NULL OR
                   ST_Intersects(l.position,ST_MakeEnvelope($2,$3,$4,$5,4326)::geography))
              AND ($7::double precision IS NULL OR
                   ST_DWithin(l.position,ST_SetSRID(ST_MakePoint($8,$7),4326)::geography,$9))
            GROUP BY l.id
            ORDER BY
              CASE WHEN $7::double precision IS NOT NULL THEN
                ST_Distance(l.position,ST_SetSRID(ST_MakePoint($8,$7),4326)::geography)
              END NULLS LAST,
              l.id
            LIMIT $6
            """, connection);

        command.Parameters.AddWithValue(query is null ? DBNull.Value : query.Trim());
        AddNullableDouble(command, bbox?.MinLon);
        AddNullableDouble(command, bbox?.MinLat);
        AddNullableDouble(command, bbox?.MaxLon);
        AddNullableDouble(command, bbox?.MaxLat);
        command.Parameters.AddWithValue(limit);
        AddNullableDouble(command, latitude);
        AddNullableDouble(command, longitude);
        command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Integer, Value = effectiveRadius is null ? DBNull.Value : effectiveRadius.Value });

        var result = new List<LocationListItem>();
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            var updated = NullableTimestamp(reader, reader.GetOrdinal("source_updated_at"));
            result.Add(new LocationListItem(
                reader.GetInt64(0), reader.GetString(1), reader.GetString(2), reader.GetString(3),
                reader.GetString(4), reader.GetString(5), NullableString(reader, 6),
                NullableDouble(reader, 7), NullableDouble(reader, 8),
                reader.GetInt32(9), reader.GetInt32(10), reader.GetInt32(11), reader.GetInt32(12), reader.GetInt32(13),
                updated, IsStale(updated), NullableDouble(reader, reader.GetOrdinal("distance_meters"))));
        }
        return result;
    }

    public async Task<LocationDetail?> GetLocation(long id)
    {
        await using var connection = new NpgsqlConnection(_connectionString);
        await connection.OpenAsync();

        LocationDetail? location;
        await using (var command = new NpgsqlCommand("""
            SELECT l.id,l.country_code,l.party_id,l.source_id,l.address,l.city,l.operator_name,
                   l.latitude,l.longitude,GREATEST(l.source_last_updated,max(e.source_last_updated))
            FROM charging_location l LEFT JOIN evse e ON e.location_id=l.id
            WHERE l.id=$1 GROUP BY l.id
            """, connection))
        {
            command.Parameters.AddWithValue(id);
            await using var reader = await command.ExecuteReaderAsync();
            if (!await reader.ReadAsync()) return null;
            var updated = NullableTimestamp(reader, 9);
            location = new LocationDetail
            {
                Id=reader.GetInt64(0), CountryCode=reader.GetString(1), PartyId=reader.GetString(2),
                SourceId=reader.GetString(3), Address=reader.GetString(4), City=reader.GetString(5),
                OperatorName=NullableString(reader,6), Latitude=NullableDouble(reader,7), Longitude=NullableDouble(reader,8),
                SourceUpdatedAt=updated, IsStale=IsStale(updated)
            };
        }

        var evses = new Dictionary<long, EvseDetail>();
        var connectors = new Dictionary<long, ConnectorDetail>();
        await using var details = new NpgsqlCommand("""
            SELECT e.id,e.source_uid,e.external_evse_id,e.status,e.source_last_updated,
                   c.id,c.source_id,c.standard,c.power_type,c.max_electric_power,
                   t.country_code,t.party_id,t.source_id,t.currency,t.details::text,t.source_last_updated
            FROM evse e
            LEFT JOIN connector c ON c.evse_id=e.id
            LEFT JOIN connector_tariff ct ON ct.connector_id=c.id
            LEFT JOIN tariff t ON t.country_code=ct.tariff_country_code
                              AND t.party_id=ct.tariff_party_id
                              AND t.source_id=ct.tariff_source_id
            WHERE e.location_id=$1
            ORDER BY e.id,c.id,t.id
            """, connection);
        details.Parameters.AddWithValue(id);
        await using var detailReader = await details.ExecuteReaderAsync();
        while (await detailReader.ReadAsync())
        {
            var evseId = detailReader.GetInt64(0);
            if (!evses.TryGetValue(evseId, out var evse))
            {
                evse = new EvseDetail
                {
                    Id=evseId, SourceUid=detailReader.GetString(1), ExternalEvseId=NullableString(detailReader,2),
                    Status=detailReader.GetString(3), SourceUpdatedAt=NullableTimestamp(detailReader,4)
                };
                evses.Add(evseId, evse);
                location.Evses.Add(evse);
            }
            if (detailReader.IsDBNull(5)) continue;
            var connectorId = detailReader.GetInt64(5);
            if (!connectors.TryGetValue(connectorId, out var connector))
            {
                connector = new ConnectorDetail
                {
                    Id=connectorId, SourceId=detailReader.GetString(6), Standard=NullableString(detailReader,7),
                    PowerType=NullableString(detailReader,8), MaxElectricPower=detailReader.IsDBNull(9)?null:detailReader.GetInt32(9)
                };
                connectors.Add(connectorId, connector);
                evse.Connectors.Add(connector);
            }
            if (detailReader.IsDBNull(10)) continue;
            using var document = JsonDocument.Parse(detailReader.GetString(14));
            connector.Tariffs.Add(new TariffDetail(
                detailReader.GetString(10),detailReader.GetString(11),detailReader.GetString(12),
                NullableString(detailReader,13),document.RootElement.Clone(),NullableTimestamp(detailReader,15)));
        }
        return location;
    }

    public async Task<LocationStatistics?> GetStatistics(long locationId)
    {
        await using var connection = new NpgsqlConnection(_connectionString);
        await connection.OpenAsync();

        await using (var exists = new NpgsqlCommand("SELECT 1 FROM charging_location WHERE id=$1", connection))
        {
            exists.Parameters.AddWithValue(locationId);
            if (await exists.ExecuteScalarAsync() is null) return null;
        }

        var locationSlots = new List<AvailabilitySlot>();
        await using (var command = new NpgsqlCommand("""
            SELECT iso_weekday,category_ewma,sample_count,changed_feed_count,unchanged_feed_count,last_sample_at
            FROM location_snapshot_weekday_profile WHERE location_id=$1 ORDER BY iso_weekday
            """, connection))
        {
            command.Parameters.AddWithValue(locationId);
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync())
                AppendSlots(locationSlots, reader, categoryCount:5);
        }

        var byEvse = new Dictionary<long,List<AvailabilitySlot>>();
        await using (var command = new NpgsqlCommand("""
            SELECT p.evse_id,p.iso_weekday,p.category_ewma,p.sample_count,p.changed_feed_count,
                   p.unchanged_feed_count,p.last_sample_at
            FROM evse_snapshot_weekday_profile p
            JOIN evse e ON e.id=p.evse_id
            WHERE e.location_id=$1 ORDER BY p.evse_id,p.iso_weekday
            """, connection))
        {
            command.Parameters.AddWithValue(locationId);
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync())
            {
                var id=reader.GetInt64(0);
                if (!byEvse.TryGetValue(id,out var slots)) byEvse[id]=slots=[];
                AppendSlots(slots, reader, categoryCount:9, offset:1);
            }
        }

        return new LocationStatistics(locationId, locationSlots,
            byEvse.Select(x => new EvseStatistics(x.Key,x.Value)).ToArray());
    }

    public async Task<DataStatus> GetDataStatus()
    {
        await using var connection = new NpgsqlConnection(_connectionString);
        await connection.OpenAsync();
        await using var command = new NpgsqlCommand("""
            SELECT (SELECT max(completed_at) FROM import_run WHERE status='completed'),
                   (SELECT count(*) FROM charging_location),
                   (SELECT count(*) FROM evse),
                   (SELECT count(*) FROM connector),
                   (SELECT count(*) FROM tariff)
            """, connection);
        await using var reader = await command.ExecuteReaderAsync();
        await reader.ReadAsync();
        var completed = NullableTimestamp(reader,0);
        var age = completed is null ? (double?)null : (DateTimeOffset.UtcNow-completed.Value).TotalSeconds;
        return new DataStatus(completed,age,IsStale(completed),(int)_staleAfter.TotalMinutes,
            reader.GetInt64(1),reader.GetInt64(2),reader.GetInt64(3),reader.GetInt64(4));
    }

    private void AppendSlots(List<AvailabilitySlot> target, NpgsqlDataReader reader, int categoryCount, int offset=0)
    {
        var weekday=reader.GetInt16(offset);
        var ewma=reader.GetFieldValue<float?[]>(offset+1);
        var samples=reader.GetFieldValue<int[]>(offset+2);
        var changed=reader.GetFieldValue<int[]>(offset+3);
        var unchanged=reader.GetFieldValue<int[]>(offset+4);
        var last=NullableTimestamp(reader,offset+5);
        if (ewma.Length != categoryCount*24 || samples.Length != 24)
            throw new InvalidDataException("Unexpected compact statistics array shape.");
        for (var hour=0; hour<24; hour++)
            target.Add(new AvailabilitySlot(weekday,hour,ewma[hour],samples[hour],changed[hour],unchanged[hour],last));
    }

    private bool IsStale(DateTimeOffset? timestamp)
        => timestamp is null || DateTimeOffset.UtcNow-timestamp.Value > _staleAfter;

    private static void AddNullableDouble(NpgsqlCommand command,double? value)
        => command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType=NpgsqlDbType.Double, Value=value is null?DBNull.Value:value.Value });
    private static string? NullableString(NpgsqlDataReader reader,int index) => reader.IsDBNull(index)?null:reader.GetString(index);
    private static double? NullableDouble(NpgsqlDataReader reader,int index) => reader.IsDBNull(index)?null:reader.GetDouble(index);
    private static DateTimeOffset? NullableTimestamp(NpgsqlDataReader reader,int index)
        => reader.IsDBNull(index)?null:reader.GetFieldValue<DateTimeOffset>(index);
}
