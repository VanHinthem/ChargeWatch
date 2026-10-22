using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using Npgsql;

internal static class BulkImporter
{
    private static string Required(JsonElement e, string property)
        => e.TryGetProperty(property, out var v) && v.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(v.GetString())
            ? v.GetString()! : throw new InvalidDataException($"Missing {property}");

    private static string? Optional(JsonElement e, string property)
        => e.TryGetProperty(property, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static IEnumerable<JsonElement> Items(JsonElement e, string property)
        => e.TryGetProperty(property, out var v) && v.ValueKind == JsonValueKind.Array ? v.EnumerateArray() : [];

    private static string Key(params string[] parts) => string.Join("\u001f", parts);

    private static async IAsyncEnumerable<JsonElement> Read(string path)
    {
        await using var file = File.OpenRead(path);
        await using var stream = new GZipStream(file, CompressionMode.Decompress);
        await foreach (var item in JsonSerializer.DeserializeAsyncEnumerable<JsonElement>(stream))
            yield return item;
    }

    private static async Task Copy(NpgsqlConnection db, string table, IEnumerable<string> lines)
    {
        await using var writer = await db.BeginTextImportAsync($"COPY {table}(payload) FROM STDIN");
        foreach (var line in lines)
            await writer.WriteLineAsync(line);
    }

    private static async Task Exec(NpgsqlConnection db, NpgsqlTransaction tx, string sql)
    {
        await using var command = new NpgsqlCommand(sql, db, tx) { CommandTimeout = 600 };
        await command.ExecuteNonQueryAsync();
    }

    private static string Wrap(string type, string country, string party, string id, JsonElement data, string? parent = null)
        => JsonSerializer.Serialize(new { type, country, party, id, parent, data });

    public static async Task<int> Run(string[] args)
    {
        if (args.Length is < 1 or > 3)
        {
            Console.Error.WriteLine("Usage: --bulk <locations.json.gz> [tariffs.json.gz] [minLocations]");
            return 2;
        }
        var connectionString = Environment.GetEnvironmentVariable("CW_IMPORT_CONNECTION");
        if (string.IsNullOrWhiteSpace(connectionString))
        {
            Console.Error.WriteLine("Set CW_IMPORT_CONNECTION.");
            return 2;
        }
        var minimum = args.Length == 3 && int.TryParse(args[2], out var n) ? n : 1;
        if (minimum < 1) throw new ArgumentException("minLocations must be positive");
        var locationsPath = args[0];
        var tariffsPath = args.Length > 1 ? args[1] : null;
        var stopwatch = Stopwatch.StartNew();
        var seenLocations = new HashSet<string>(StringComparer.Ordinal);
        var seenEvses = new HashSet<string>(StringComparer.Ordinal);
        var seenConnectors = new HashSet<string>(StringComparer.Ordinal);
        var seenTariffs = new HashSet<string>(StringComparer.Ordinal);
        var locCount = 0;
        var evseCount = 0;
        var connectorCount = 0;
        await using var db = new NpgsqlConnection(connectionString);
        await db.OpenAsync();
        await using var tx = await db.BeginTransactionAsync();
        try
        {
            // Temporary staging is session-private and disappears on commit/rollback.
            await Exec(db, tx, """
                CREATE TEMP TABLE stage_loc(payload jsonb) ON COMMIT DROP;
                CREATE TEMP TABLE stage_evse(payload jsonb) ON COMMIT DROP;
                CREATE TEMP TABLE stage_connector(payload jsonb) ON COMMIT DROP;
                CREATE TEMP TABLE stage_tariff(payload jsonb) ON COMMIT DROP;
                """);

            // Avoid buffering the entire national source: COPY one compact line at a time.
            // Npgsql text COPY accepts one JSON value per line. JsonSerializer.Serialize escapes newlines.
            await using (var writer = await db.BeginTextImportAsync("COPY stage_loc(payload) FROM STDIN"))
            {
                await foreach (var loc in Read(locationsPath))
                {
                    var country = Required(loc, "country_code");
                    var party = Required(loc, "party_id");
                    var id = Required(loc, "id");
                    var key = Key(country, party, id);
                    if (!seenLocations.Add(key)) throw new InvalidDataException($"Duplicate location {key}");
                    locCount++;
                    await writer.WriteLineAsync(JsonSerializer.Serialize(new { country, party, id, data = loc }));
                }
            }
            if (locCount < minimum)
                throw new InvalidDataException($"Only {locCount} locations; expected >= {minimum}");

            // Re-read gzip to flatten EVSE/connector objects without retaining all locations in RAM.
            await using (var ew = await db.BeginTextImportAsync("COPY stage_evse(payload) FROM STDIN"))
            {
                await foreach (var loc in Read(locationsPath))
                {
                    var country = Required(loc, "country_code");
                    var party = Required(loc, "party_id");
                    var id = Required(loc, "id");
                    foreach (var evse in Items(loc, "evses"))
                    {
                        var uid = Required(evse, "uid");
                        if (!seenEvses.Add(Key(country, party, id, uid)))
                            throw new InvalidDataException($"Duplicate EVSE {uid}");
                        evseCount++;
                        _ = Required(evse, "status");
                        await ew.WriteLineAsync(JsonSerializer.Serialize(new { country, party, id, uid, data = evse }));
                    }
                }
            }
            await using (var cw = await db.BeginTextImportAsync("COPY stage_connector(payload) FROM STDIN"))
            {
                await foreach (var loc in Read(locationsPath))
                {
                    var country = Required(loc, "country_code");
                    var party = Required(loc, "party_id");
                    var id = Required(loc, "id");
                    foreach (var evse in Items(loc, "evses"))
                    {
                        var uid = Required(evse, "uid");
                        foreach (var connector in Items(evse, "connectors"))
                        {
                            var connectorId = Required(connector, "id");
                            if (!seenConnectors.Add(Key(country, party, id, uid, connectorId)))
                                throw new InvalidDataException($"Duplicate connector {connectorId}");
                            connectorCount++;
                            await cw.WriteLineAsync(JsonSerializer.Serialize(new { country, party, id, uid, connectorId, data = connector }));
                        }
                    }
                }
            }

            if (tariffsPath is not null)
            {
                await using var tw = await db.BeginTextImportAsync("COPY stage_tariff(payload) FROM STDIN");
                await foreach (var tariff in Read(tariffsPath))
                {
                    var country = Required(tariff, "country_code");
                    var party = Required(tariff, "party_id");
                    var id = Required(tariff, "id");
                    if (!seenTariffs.Add(Key(country, party, id)))
                        throw new InvalidDataException($"Duplicate tariff {id}");
                    await tw.WriteLineAsync(JsonSerializer.Serialize(new { country, party, id, data = tariff }));
                }
            }

            Console.WriteLine($"Staged: {locCount} locations, {evseCount} EVSEs, {connectorCount} connectors, {seenTariffs.Count} tariffs. Publishing...");
            await Exec(db, tx, """
                INSERT INTO charging_location(country_code,party_id,source_id,address,city,operator_name,latitude,longitude,position,source_last_updated)
                SELECT payload->>'country',payload->>'party',payload->>'id',
                       COALESCE(payload->'data'->>'address',''), COALESCE(payload->'data'->>'city',''),
                       payload->'data'->'operator'->>'name',
                       NULLIF(payload->'data'->'coordinates'->>'latitude','')::double precision,
                       NULLIF(payload->'data'->'coordinates'->>'longitude','')::double precision,
                       CASE WHEN payload->'data'->'coordinates'->>'latitude' IS NOT NULL
                                  AND payload->'data'->'coordinates'->>'longitude' IS NOT NULL
                            THEN ST_SetSRID(ST_MakePoint(
                                   (payload->'data'->'coordinates'->>'longitude')::double precision,
                                   (payload->'data'->'coordinates'->>'latitude')::double precision),4326)::geography END,
                       NULLIF(payload->'data'->>'last_updated','')::timestamptz
                FROM stage_loc
                ON CONFLICT(country_code,party_id,source_id) DO UPDATE SET
                  address=excluded.address, city=excluded.city, operator_name=excluded.operator_name,
                  latitude=excluded.latitude, longitude=excluded.longitude, position=excluded.position,
                  source_last_updated=excluded.source_last_updated;
                """);
            await Exec(db, tx, """
                INSERT INTO evse(location_id,source_uid,external_evse_id,status,source_last_updated)
                SELECT l.id, s.payload->>'uid', s.payload->'data'->>'evse_id',
                       s.payload->'data'->>'status',
                       NULLIF(s.payload->'data'->>'last_updated','')::timestamptz
                FROM stage_evse s
                JOIN charging_location l ON l.country_code=s.payload->>'country'
                      AND l.party_id=s.payload->>'party' AND l.source_id=s.payload->>'id'
                ON CONFLICT(location_id,source_uid) DO UPDATE SET
                  external_evse_id=excluded.external_evse_id, status=excluded.status,
                  source_last_updated=excluded.source_last_updated;
                """);
            await Exec(db, tx, """
                INSERT INTO connector(evse_id,source_id,standard,power_type,max_electric_power)
                SELECT e.id,s.payload->>'connectorId',s.payload->'data'->>'standard',
                       s.payload->'data'->>'power_type',
                       NULLIF(s.payload->'data'->>'max_electric_power','')::integer
                FROM stage_connector s
                JOIN charging_location l ON l.country_code=s.payload->>'country'
                   AND l.party_id=s.payload->>'party' AND l.source_id=s.payload->>'id'
                JOIN evse e ON e.location_id=l.id AND e.source_uid=s.payload->>'uid'
                ON CONFLICT(evse_id,source_id) DO UPDATE SET
                   standard=excluded.standard,power_type=excluded.power_type,
                   max_electric_power=excluded.max_electric_power;
                """);
            await Exec(db, tx, """
                DELETE FROM connector_tariff ct USING connector c, evse e, charging_location l, stage_connector s
                WHERE ct.connector_id=c.id AND c.evse_id=e.id AND e.location_id=l.id
                  AND l.country_code=s.payload->>'country' AND l.party_id=s.payload->>'party'
                  AND l.source_id=s.payload->>'id' AND e.source_uid=s.payload->>'uid'
                  AND c.source_id=s.payload->>'connectorId';
                INSERT INTO connector_tariff(connector_id,tariff_country_code,tariff_party_id,tariff_source_id)
                SELECT DISTINCT c.id,s.payload->>'country',s.payload->>'party', t.tariff_id
                FROM stage_connector s
                JOIN charging_location l ON l.country_code=s.payload->>'country'
                   AND l.party_id=s.payload->>'party' AND l.source_id=s.payload->>'id'
                JOIN evse e ON e.location_id=l.id AND e.source_uid=s.payload->>'uid'
                JOIN connector c ON c.evse_id=e.id AND c.source_id=s.payload->>'connectorId'
                CROSS JOIN LATERAL jsonb_array_elements_text(
                   CASE WHEN jsonb_typeof(s.payload->'data'->'tariff_ids')='array'
                        THEN s.payload->'data'->'tariff_ids' ELSE '[]'::jsonb END
                ) AS t(tariff_id)
                WHERE length(t.tariff_id)>0
                ON CONFLICT DO NOTHING;
                """);
            if (tariffsPath is not null)
                await Exec(db, tx, """
                    INSERT INTO tariff(country_code,party_id,source_id,currency,details,source_last_updated)
                    SELECT payload->>'country',payload->>'party',payload->>'id',
                           payload->'data'->>'currency',payload->'data',
                           NULLIF(payload->'data'->>'last_updated','')::timestamptz
                    FROM stage_tariff
                    ON CONFLICT(country_code,party_id,source_id) DO UPDATE SET
                      currency=excluded.currency,details=excluded.details,
                      source_last_updated=excluded.source_last_updated;
                    """);

            string sha;
            await using (var source = File.OpenRead(locationsPath))
                sha = Convert.ToHexString(SHA256.HashData(source)).ToLowerInvariant();
            await using (var cmd = new NpgsqlCommand("""
                INSERT INTO import_run(source_name,source_sha256,status,completed_at,location_count,evse_count,connector_count)
                VALUES('ndw-ocpi-bulk',$1,'completed',now(),$2,$3,$4)
                """, db, tx))
            {
                cmd.Parameters.AddWithValue(sha);
                cmd.Parameters.AddWithValue(locCount);
                cmd.Parameters.AddWithValue(evseCount);
                cmd.Parameters.AddWithValue(connectorCount);
                await cmd.ExecuteNonQueryAsync();
            }
            await tx.CommitAsync();
            Console.WriteLine($"Bulk committed: {locCount} locations, {evseCount} EVSEs, {connectorCount} connectors, {seenTariffs.Count} tariffs in {stopwatch.Elapsed}.");
            return 0;
        }
        catch (Exception e)
        {
            await tx.RollbackAsync();
            Console.Error.WriteLine($"Bulk rollback: {e.Message}");
            return 1;
        }
    }
}
