using System.Text.Json;
using Npgsql;
using NpgsqlTypes;

internal sealed partial class ApiDatabase
{
    /// <summary>
    /// Resolve up to 20 NDW identities using one indexed batch lookup, then
    /// fetch their complete detail and optional history through fixed-count
    /// SQL queries. No N+1 queries; only SELECTs under chargewatch_read.
    /// </summary>
    public async Task<LocationBatchResponse> GetLocationBatch(
        IReadOnlyList<(string FeatureId, NdwLocationKey Key)> requested,
        bool includeStatistics)
    {
        if (requested.Count is < 1 or > LocationBatchContracts.MaxLocations)
            throw new ArgumentOutOfRangeException(nameof(requested));

        await using var connection = new NpgsqlConnection(_connectionString);
        await connection.OpenAsync();
        var feedObservedAt = await GetFeedLastObservedAt(connection);
        var tariffsCheckedAt = await GetTariffsFeedLastCheckedAt(connection);
        var locations = await LoadBatchLocations(connection, requested, feedObservedAt, tariffsCheckedAt);
        var ids = locations.Keys.ToArray();
        if (ids.Length > 0)
            await LoadBatchEvseAndTariffs(connection, ids, locations);

        IReadOnlyDictionary<long, LocationStatistics> statistics =
            includeStatistics && ids.Length > 0
                ? await LoadBatchStatistics(connection, ids)
                : new Dictionary<long, LocationStatistics>();

        var byKey = locations.Values.ToDictionary(
            x => new NdwLocationKey(x.CountryCode, x.PartyId, x.SourceId));
        return new LocationBatchResponse(requested.Select(item =>
        {
            if (!byKey.TryGetValue(item.Key, out var location))
                return new LocationBatchEntry(item.FeatureId, "NOT_FOUND", null, null);
            statistics.TryGetValue(location.Id, out var history);
            return new LocationBatchEntry(item.FeatureId, "FOUND", location, history);
        }).ToArray());
    }

    private async Task<Dictionary<long, LocationDetail>> LoadBatchLocations(
        NpgsqlConnection connection,
        IReadOnlyList<(string FeatureId, NdwLocationKey Key)> requested,
        DateTimeOffset? feedObservedAt,
        DateTimeOffset? tariffsCheckedAt)
    {
        await using var command = new NpgsqlCommand("""
            SELECT l.id,l.country_code,l.party_id,l.source_id,l.address,l.city,l.operator_name,
                   l.latitude,l.longitude,
                   GREATEST(l.source_last_updated,max(e.source_last_updated))
            FROM unnest($1::text[],$2::text[],$3::text[])
                 AS requested(country_code,party_id,source_id)
            JOIN charging_location l
              ON l.country_code=requested.country_code
             AND l.party_id=requested.party_id
             AND l.source_id=requested.source_id AND l.is_active
            LEFT JOIN evse e ON e.location_id=l.id AND e.is_active
            GROUP BY l.id
            """, connection);
        AddTextArray(command, requested.Select(x => x.Key.CountryCode).ToArray());
        AddTextArray(command, requested.Select(x => x.Key.PartyId).ToArray());
        AddTextArray(command, requested.Select(x => x.Key.SourceId).ToArray());
        var result = new Dictionary<long, LocationDetail>();
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            var updated = NullableTimestamp(reader, 9);
            var location = new LocationDetail
            {
                Id=reader.GetInt64(0),
                CountryCode=reader.GetString(1),
                PartyId=reader.GetString(2),
                SourceId=reader.GetString(3),
                Address=reader.GetString(4),
                City=reader.GetString(5),
                OperatorName=NullableString(reader,6),
                Latitude=NullableDouble(reader,7),
                Longitude=NullableDouble(reader,8),
                SourceUpdatedAt=updated,
                IsStale=IsStale(updated),
                FeedLastObservedAt=feedObservedAt,
                FeedIsStale=IsStale(feedObservedAt),
                TariffsFeedLastCheckedAt=tariffsCheckedAt,
                TariffsFeedIsStale=IsTariffsFeedStale(tariffsCheckedAt)
            };
            result.Add(location.Id, location);
        }
        return result;
    }

    private static void AddTextArray(NpgsqlCommand command, string[] values)
        => command.Parameters.Add(new NpgsqlParameter
        {
            NpgsqlDbType=NpgsqlDbType.Array | NpgsqlDbType.Text,
            Value=values
        });

    private static void AddIds(NpgsqlCommand command, long[] ids)
        => command.Parameters.Add(new NpgsqlParameter
        {
            NpgsqlDbType=NpgsqlDbType.Array | NpgsqlDbType.Bigint,
            Value=ids
        });

    private async Task LoadBatchEvseAndTariffs(
        NpgsqlConnection connection, long[] ids, Dictionary<long, LocationDetail> locations)
    {
        await using var command = new NpgsqlCommand("""
            SELECT e.location_id,
                   e.id,e.source_uid,e.external_evse_id,e.status,e.source_last_updated,
                   c.id,c.source_id,c.standard,c.power_type,c.max_electric_power,
                   ct.tariff_country_code,ct.tariff_party_id,ct.tariff_source_id,
                   t.currency,t.details::text,t.source_last_updated,
                   c.format,c.max_voltage,c.max_amperage
            FROM evse e
            LEFT JOIN connector c ON c.evse_id=e.id AND c.is_active
            LEFT JOIN connector_tariff ct ON ct.connector_id=c.id
            LEFT JOIN tariff t ON t.country_code=ct.tariff_country_code
                              AND t.party_id=ct.tariff_party_id
                              AND t.source_id=ct.tariff_source_id
                              AND t.is_active
            WHERE e.location_id = ANY($1::bigint[]) AND e.is_active
            ORDER BY e.location_id,e.id,c.id,ct.tariff_country_code,
                     ct.tariff_party_id,ct.tariff_source_id
            """, connection);
        AddIds(command, ids);
        var evses = new Dictionary<long, EvseDetail>();
        var connectors = new Dictionary<long, ConnectorDetail>();
        var tariffAsOfUtc = DateTimeOffset.UtcNow;
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            var locationId = reader.GetInt64(0);
            if (!locations.TryGetValue(locationId, out var location)) continue;
            var evseId = reader.GetInt64(1);
            if (!evses.TryGetValue(evseId, out var evse))
            {
                evse = new EvseDetail
                {
                    Id=evseId,
                    SourceUid=reader.GetString(2),
                    ExternalEvseId=NullableString(reader,3),
                    Status=reader.GetString(4),
                    SourceUpdatedAt=NullableTimestamp(reader,5)
                };
                evses.Add(evseId, evse);
                location.Evses.Add(evse);
            }
            if (reader.IsDBNull(6)) continue;
            var connectorId = reader.GetInt64(6);
            if (!connectors.TryGetValue(connectorId, out var connector))
            {
                connector = new ConnectorDetail
                {
                    Id=connectorId,
                    SourceId=reader.GetString(7),
                    Standard=NullableString(reader,8),
                    PowerType=NullableString(reader,9),
                    MaxElectricPower=reader.IsDBNull(10)?null:reader.GetInt32(10),
                    Format=NullableString(reader,17),
                    MaxVoltage=reader.IsDBNull(18)?null:reader.GetInt32(18),
                    MaxAmperage=reader.IsDBNull(19)?null:reader.GetInt32(19)
                };
                connectors.Add(connectorId, connector);
                evse.Connectors.Add(connector);
            }
            if (reader.IsDBNull(11)) continue;
            var tariffCountry=reader.GetString(11);
            var tariffParty=reader.GetString(12);
            var tariffSource=reader.GetString(13);
            if (reader.IsDBNull(15))
            {
                connector.TariffOptions.Add(ConnectorTariffContract.Missing(
                    tariffCountry,tariffParty,tariffSource));
                continue;
            }
            using var document = JsonDocument.Parse(reader.GetString(15));
            connector.TariffOptions.Add(ConnectorTariffContract.Evaluate(
                tariffCountry,tariffParty,tariffSource,NullableString(reader,14),
                document.RootElement,NullableTimestamp(reader,16),tariffAsOfUtc));
        }
    }

    private async Task<IReadOnlyDictionary<long, LocationStatistics>> LoadBatchStatistics(
        NpgsqlConnection connection, long[] ids)
    {
        var locationSlots = ids.ToDictionary(id => id, _ => new List<AvailabilitySlot>());
        await using (var command = new NpgsqlCommand("""
            SELECT location_id,iso_weekday,category_ewma,sample_count,changed_feed_count,
                   unchanged_feed_count,last_sample_at,available_evse_ewma,active_evse_ewma,
                   valid_count_samples,excluded_count_samples
            FROM location_snapshot_weekday_profile
            WHERE location_id = ANY($1::bigint[])
            ORDER BY location_id,iso_weekday
            """, connection))
        {
            AddIds(command, ids);
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync())
                AppendSlots(locationSlots[reader.GetInt64(0)],reader,
                    categoryCount:5,offset:1,includeCounts:true);
        }

        var byEvse = ids.ToDictionary(id=>id,_=>new Dictionary<long,List<AvailabilitySlot>>());
        await using (var command = new NpgsqlCommand("""
            SELECT e.location_id,p.evse_id,p.iso_weekday,p.category_ewma,p.sample_count,
                   p.changed_feed_count,p.unchanged_feed_count,p.last_sample_at
            FROM evse_snapshot_weekday_profile p
            JOIN evse e ON e.id=p.evse_id AND e.is_active
            WHERE e.location_id = ANY($1::bigint[])
            ORDER BY e.location_id,p.evse_id,p.iso_weekday
            """, connection))
        {
            AddIds(command, ids);
            await using var reader = await command.ExecuteReaderAsync();
            while (await reader.ReadAsync())
            {
                var locationId = reader.GetInt64(0);
                var evseId = reader.GetInt64(1);
                var dict = byEvse[locationId];
                if (!dict.TryGetValue(evseId,out var slots)) dict[evseId]=slots=[];
                AppendSlots(slots,reader,categoryCount:9,offset:2);
            }
        }
        return ids.ToDictionary(id=>id,id=>new LocationStatistics(
            id,locationSlots[id],
            byEvse[id].Select(pair=>new EvseStatistics(pair.Key,pair.Value)).ToArray()));
    }
}
