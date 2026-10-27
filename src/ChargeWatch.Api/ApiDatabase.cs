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
        var feedObservedAt = await GetFeedLastObservedAt(connection);

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
            WHERE l.is_active
              AND ($1::text IS NULL OR l.address ILIKE '%'||$1||'%' OR l.city ILIKE '%'||$1||'%'
                   OR coalesce(l.operator_name,'') ILIKE '%'||$1||'%')
              AND ($2::double precision IS NULL OR
                   ST_Intersects(l.position,ST_MakeEnvelope($2,$3,$4,$5,4326)::geography))
              AND ($7::double precision IS NULL OR
                   ST_DWithin(l.position,ST_SetSRID(ST_MakePoint($8,$7),4326)::geography,$9))
            GROUP BY l.id
            ORDER BY
              CASE WHEN $1::text IS NOT NULL AND trim($1) <> '' THEN
                CASE WHEN lower(l.city)=lower(trim($1)) THEN 0
                     WHEN lower(l.address)=lower(trim($1)) THEN 1
                     WHEN lower(l.city) LIKE lower(trim($1))||'%' THEN 2
                     WHEN lower(l.address) LIKE lower(trim($1))||'%' THEN 3
                     ELSE 4 END
                ELSE 0 END,
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
                updated, IsStale(updated), feedObservedAt, IsStale(feedObservedAt),
                NullableDouble(reader, reader.GetOrdinal("distance_meters"))));
        }
        return result;
    }


    // A dedicated map path: a bounded, read-only viewport query. Never return
    // a deceptively complete partial result when the viewport exceeds the cap.
    public async Task<MapViewportResult> GetMapViewport(Bbox bbox)
    {
        await using var connection = new NpgsqlConnection(_connectionString);
        await connection.OpenAsync();
        await using var command = new NpgsqlCommand("""
            WITH selected AS (
                SELECT id,country_code,party_id,source_id,address,operator_name,
                       longitude,latitude,source_last_updated
                FROM charging_location
                WHERE is_active AND position IS NOT NULL
                  AND ST_Intersects(position, ST_MakeEnvelope($1,$2,$3,$4,4326)::geography)
                ORDER BY id LIMIT 1000
            )
            SELECT l.country_code,l.party_id,l.source_id,l.address,l.operator_name,
                   l.longitude,l.latitude,
                   GREATEST(l.source_last_updated,max(e.source_last_updated)) AS updated_at,
                   count(DISTINCT e.id) FILTER (WHERE upper(e.status)='AVAILABLE')::int AS available_count,
                   count(DISTINCT e.id)::int AS total_count,
                   COALESCE(jsonb_agg(DISTINCT jsonb_build_object(
                       'available', CASE WHEN upper(e.status)='AVAILABLE' THEN 1 ELSE 0 END,
                       'status',upper(e.status),
                       'evseId',e.id,
                       'connectorType',c.standard,
                       'powerType',c.power_type,
                       'powerMax',c.max_electric_power
                   )) FILTER (WHERE e.id IS NOT NULL AND c.id IS NOT NULL),'[]'::jsonb)::text AS connectors
            FROM selected l
            LEFT JOIN evse e ON e.location_id=l.id
            LEFT JOIN connector c ON c.evse_id=e.id
            GROUP BY l.id,l.country_code,l.party_id,l.source_id,l.address,l.operator_name,
                     l.longitude,l.latitude,l.source_last_updated
            ORDER BY l.id
            """, connection);
        command.Parameters.AddWithValue(bbox.MinLon);
        command.Parameters.AddWithValue(bbox.MinLat);
        command.Parameters.AddWithValue(bbox.MaxLon);
        command.Parameters.AddWithValue(bbox.MaxLat);
        var locations = new List<MapLocationItem>();
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            var id = $"{reader.GetString(0)}-{reader.GetString(1)}-{reader.GetString(2)}";
            using var raw = JsonDocument.Parse(reader.GetString(10));
            var categoryRows = raw.RootElement.EnumerateArray()
                .Select(x => new {
                    EvseId = x.GetProperty("evseId").GetInt64(),
                    Available = x.GetProperty("available").GetInt32(),
                    Type = x.GetProperty("connectorType").ValueKind == JsonValueKind.Null
                        ? (string?)null : x.GetProperty("connectorType").GetString(),
                    PowerType = x.GetProperty("powerType").ValueKind == JsonValueKind.Null
                        ? (string?)null : x.GetProperty("powerType").GetString(),
                    PowerMax = x.GetProperty("powerMax").ValueKind == JsonValueKind.Null
                        ? (int?)null : x.GetProperty("powerMax").GetInt32()
                }).ToArray();
            var categories = categoryRows.GroupBy(x => (x.Type,x.PowerType,x.PowerMax))
                .Select(g => new MapConnectorCategory(
                    g.Where(x => x.Available == 1).Select(x => x.EvseId).Distinct().Count(),
                    g.Select(x => x.EvseId).Distinct().Count(),g.Key.Type,g.Key.PowerType,g.Key.PowerMax))
                .ToArray();
            locations.Add(new MapLocationItem(id,reader.GetDouble(5),reader.GetDouble(6),
                reader.GetString(3),NullableString(reader,4),NullableTimestamp(reader,7),
                reader.GetInt32(8),reader.GetInt32(9),categories));
        }
        return new MapViewportResult(locations,locations.Count >= 1000);
    }

    // Resolve by the compound OCPI key. Never infer identity from nearby coordinates.
    public async Task<long?> FindLocationId(NdwLocationKey key)
    {
        await using var connection = new NpgsqlConnection(_connectionString);
        await connection.OpenAsync();
        await using var command = new NpgsqlCommand("""
            SELECT id FROM charging_location
            WHERE country_code=$1 AND party_id=$2 AND source_id=$3 AND is_active
            """, connection);
        command.Parameters.AddWithValue(key.CountryCode);
        command.Parameters.AddWithValue(key.PartyId);
        command.Parameters.AddWithValue(key.SourceId);
        var id = await command.ExecuteScalarAsync();
        return id is null or DBNull ? null : (long)id;
    }

    public async Task<LocationDetail?> GetLocation(long id)
    {
        await using var connection = new NpgsqlConnection(_connectionString);
        await connection.OpenAsync();

        var feedObservedAt = await GetFeedLastObservedAt(connection);
        LocationDetail? location;
        await using (var command = new NpgsqlCommand("""
            SELECT l.id,l.country_code,l.party_id,l.source_id,l.address,l.city,l.operator_name,
                   l.latitude,l.longitude,GREATEST(l.source_last_updated,max(e.source_last_updated))
            FROM charging_location l LEFT JOIN evse e ON e.location_id=l.id
            WHERE l.id=$1 AND l.is_active GROUP BY l.id
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
                SourceUpdatedAt=updated, IsStale=IsStale(updated),
                FeedLastObservedAt=feedObservedAt, FeedIsStale=IsStale(feedObservedAt)
            };
        }

        var evses = new Dictionary<long, EvseDetail>();
        var connectors = new Dictionary<long, ConnectorDetail>();
        await using var details = new NpgsqlCommand("""
            SELECT e.id,e.source_uid,e.external_evse_id,e.status,e.source_last_updated,
                   c.id,c.source_id,c.standard,c.power_type,c.max_electric_power,
                   t.country_code,t.party_id,t.source_id,t.currency,t.details::text,t.source_last_updated,
                   c.format,c.max_voltage,c.max_amperage
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
                    PowerType=NullableString(detailReader,8), MaxElectricPower=detailReader.IsDBNull(9)?null:detailReader.GetInt32(9),
                    Format=NullableString(detailReader,16),
                    MaxVoltage=detailReader.IsDBNull(17)?null:detailReader.GetInt32(17),
                    MaxAmperage=detailReader.IsDBNull(18)?null:detailReader.GetInt32(18)
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
            SELECT iso_weekday,category_ewma,sample_count,changed_feed_count,unchanged_feed_count,last_sample_at,
                   available_evse_ewma,active_evse_ewma,valid_count_samples,excluded_count_samples
            FROM location_snapshot_weekday_profile WHERE location_id=$1 ORDER BY iso_weekday
            """, connection))
        {
            command.Parameters.AddWithValue(locationId);
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync())
                AppendSlots(locationSlots, reader, categoryCount:5, includeCounts:true);
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
                   (SELECT max(last_observed_at) FROM snapshot_poll_checkpoint),
                   (SELECT count(*) FROM charging_location WHERE is_active),
                   (SELECT count(*) FROM evse),
                   (SELECT count(*) FROM connector),
                   (SELECT count(*) FROM tariff)
            """, connection);
        await using var reader = await command.ExecuteReaderAsync();
        await reader.ReadAsync();
        var completed = NullableTimestamp(reader,0);
        var observed = NullableTimestamp(reader,1);
        var age = observed is null ? (double?)null : (DateTimeOffset.UtcNow-observed.Value).TotalSeconds;
        return new DataStatus(completed,observed,age,IsStale(observed),(int)_staleAfter.TotalMinutes,
            reader.GetInt64(2),reader.GetInt64(3),reader.GetInt64(4),reader.GetInt64(5));
    }

    private void AppendSlots(List<AvailabilitySlot> target, NpgsqlDataReader reader, int categoryCount, int offset=0, bool includeCounts=false)
    {
        var weekday=reader.GetInt16(offset);
        var ewma=reader.GetFieldValue<float?[]>(offset+1);
        var samples=reader.GetFieldValue<int[]>(offset+2);
        var changed=reader.GetFieldValue<int[]>(offset+3);
        var unchanged=reader.GetFieldValue<int[]>(offset+4);
        var last=NullableTimestamp(reader,offset+5);
        if (ewma.Length != categoryCount*24 || samples.Length != 24)
            throw new InvalidDataException("Unexpected compact statistics array shape.");
        var freeEwma = includeCounts ? reader.GetFieldValue<float?[]>(offset+6) : null;
        var capacityEwma = includeCounts ? reader.GetFieldValue<float?[]>(offset+7) : null;
        var countSamples = includeCounts ? reader.GetFieldValue<int[]>(offset+8) : null;
        var excludedSamples = includeCounts ? reader.GetFieldValue<int[]>(offset+9) : null;
        if (includeCounts && (freeEwma!.Length!=24 || capacityEwma!.Length!=24 || countSamples!.Length!=24 || excludedSamples!.Length!=24))
            throw new InvalidDataException("Unexpected free EVSE history array shape.");
        for (var hour=0; hour<24; hour++)
        {
            // Category 1 is AVAILABLE for both location (>= 1 EVSE free)
            // and EVSE (status AVAILABLE). Arrays are category-major, hour-minor.
            // An EWMA is only an experimental observed-feed share, NOT a
            // calibrated prediction or an occupancy promise.
            var available = ExperimentalAvailability.AvailableShare(
                ewma[hour], samples[hour], changed[hour]);
            target.Add(new AvailabilitySlot(weekday,hour,available,
                samples[hour],changed[hour],unchanged[hour],last,
                includeCounts && countSamples![hour]>0 ? freeEwma![hour] : null,
                includeCounts && countSamples![hour]>0 ? capacityEwma![hour] : null,
                includeCounts ? countSamples![hour] : null,
                includeCounts ? excludedSamples![hour] : null));
        }
    }

    private static async Task<DateTimeOffset?> GetFeedLastObservedAt(NpgsqlConnection connection)
    {
        await using var command = new NpgsqlCommand(
            "SELECT max(last_observed_at) FROM snapshot_poll_checkpoint", connection);
        var value = await command.ExecuteScalarAsync();
        return value is null or DBNull ? null : new DateTimeOffset((DateTime)value, TimeSpan.Zero);
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
