using System.Security.Cryptography;
using System.Text.Json;
using Npgsql;

if (args.Length == 1 && args[0] == "--statistics-selftest")
{
    StatisticsHourSplitter.SelfTest();
    return StatisticsHourAccumulator.SelfTest();
}
if (args.Length > 0 && args[0] == "--watch")
    return await WatchRunner.RunContinuous();
if (args.Length > 0 && args[0] == "--watch-once")
    return await WatchRunner.RunOnce();
if (args.Length > 0 && args[0] == "--bulk")
    return await BulkImporter.Run(args[1..]);


if (args.Length < 1 || args.Length > 3)
{
    Console.Error.WriteLine("Usage: dotnet run --project src/ChargeWatch.Importer -- <locations.json.gz> [tariffs.json.gz] [minLocations]");
    return 2;
}
var conn = Environment.GetEnvironmentVariable("CW_IMPORT_CONNECTION");
if (string.IsNullOrWhiteSpace(conn))
{
    Console.Error.WriteLine("Set CW_IMPORT_CONNECTION to a PostgreSQL connection string using chargewatch_import.");
    return 2;
}
var minimum = args.Length == 3 && int.TryParse(args[2], out var n) ? n : 1;
if (minimum < 1) throw new ArgumentException("minLocations must be positive.");

static async IAsyncEnumerable<JsonElement> ReadGzip(string path)
{
    await using var file = File.OpenRead(path);
    await using var gz = new System.IO.Compression.GZipStream(file, System.IO.Compression.CompressionMode.Decompress);
    await foreach (var element in JsonSerializer.DeserializeAsyncEnumerable<JsonElement>(gz))
        yield return element;
}
static string Required(JsonElement obj, string name)
{
    if (!obj.TryGetProperty(name, out var x) || x.ValueKind != JsonValueKind.String || string.IsNullOrWhiteSpace(x.GetString()))
        throw new InvalidDataException($"Missing required {name}");
    return x.GetString()!;
}
static string? Optional(JsonElement obj, string name)
{
    return obj.TryGetProperty(name, out var x) && x.ValueKind == JsonValueKind.String ? x.GetString() : null;
}
static IEnumerable<JsonElement> Array(JsonElement obj, string property)
{
    return obj.TryGetProperty(property, out var element) && element.ValueKind == JsonValueKind.Array
        ? element.EnumerateArray() : Enumerable.Empty<JsonElement>();
}
static object Db(string? value) => value is null ? DBNull.Value : value;
static object Timestamp(string? value) => value is null ? DBNull.Value : DateTimeOffset.Parse(value);
static double? Coordinate(JsonElement obj, string property)
{
    if (!obj.TryGetProperty("coordinates", out var coords) || coords.ValueKind != JsonValueKind.Object)
        return null;
    var text = Optional(coords, property);
    return double.TryParse(text, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var value) ? value : null;
}
static async Task<long> Upsert(NpgsqlConnection db, NpgsqlTransaction tx, string sql, params object[] values)
{
    await using var cmd = new NpgsqlCommand(sql, db, tx);
    for (var i = 0; i < values.Length; i++) cmd.Parameters.AddWithValue(values[i]);
    return Convert.ToInt64(await cmd.ExecuteScalarAsync());
}
static async Task Execute(NpgsqlConnection db, NpgsqlTransaction tx, string sql, params object[] values)
{
    await using var cmd = new NpgsqlCommand(sql, db, tx);
    for (var i = 0; i < values.Length; i++) cmd.Parameters.AddWithValue(values[i]);
    await cmd.ExecuteNonQueryAsync();
}
static string Sha(string path)
{
    using var file = File.OpenRead(path);
    return Convert.ToHexString(SHA256.HashData(file)).ToLowerInvariant();
}

var locationsPath = args[0];
var tariffsPath = args.Length > 1 ? args[1] : null;
var seenLocations = new HashSet<string>(StringComparer.Ordinal);
var seenEvses = new HashSet<string>(StringComparer.Ordinal);
var seenConnectors = new HashSet<string>(StringComparer.Ordinal);
var seenTariffs = new HashSet<string>(StringComparer.Ordinal);
var countLoc = 0;
var countEvse = 0;
var countConnectors = 0;

await using var connection = new NpgsqlConnection(conn);
await connection.OpenAsync();
await using var tx = await connection.BeginTransactionAsync();
try
{
    foreach (var filename in new[] { locationsPath, tariffsPath }.Where(p => p != null))
        if (!File.Exists(filename)) throw new FileNotFoundException(filename);

    await foreach (var loc in ReadGzip(locationsPath))
    {
        var country = Required(loc, "country_code");
        var party = Required(loc, "party_id");
        var sourceId = Required(loc, "id");
        var locKey = $"{country}|{party}|{sourceId}";
        if (!seenLocations.Add(locKey)) throw new InvalidDataException($"Duplicate location {locKey}");
        var lat = Coordinate(loc, "latitude");
        var lon = Coordinate(loc, "longitude");
        var op = loc.TryGetProperty("operator", out var operatorObj) && operatorObj.ValueKind == JsonValueKind.Object
            ? Optional(operatorObj, "name") : null;
        var locationId = await Upsert(connection, tx, """
            INSERT INTO charging_location(country_code,party_id,source_id,address,city,operator_name,latitude,longitude,position,source_last_updated)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,
            CASE WHEN $7::double precision IS NULL OR $8::double precision IS NULL THEN NULL
            ELSE ST_SetSRID(ST_MakePoint($8::double precision,$7::double precision),4326)::geography END,$9)
            ON CONFLICT(country_code,party_id,source_id) DO UPDATE SET
            address=excluded.address,city=excluded.city,operator_name=excluded.operator_name,
            latitude=excluded.latitude,longitude=excluded.longitude,position=excluded.position,source_last_updated=excluded.source_last_updated
            RETURNING id
            """, country, party, sourceId, Optional(loc, "address") ?? "", Optional(loc, "city") ?? "",
            Db(op), lat is null ? DBNull.Value : lat.Value, lon is null ? DBNull.Value : lon.Value,
            Timestamp(Optional(loc, "last_updated")));
        countLoc++;
        foreach (var evse in Array(loc, "evses"))
        {
            var uid = Required(evse, "uid");
            var evseKey = $"{locKey}|{uid}";
            if (!seenEvses.Add(evseKey)) throw new InvalidDataException($"Duplicate EVSE {evseKey}");
            var evseId = await Upsert(connection, tx, """
                INSERT INTO evse(location_id,source_uid,external_evse_id,status,source_last_updated)
                VALUES($1,$2,$3,$4,$5)
                ON CONFLICT(location_id,source_uid) DO UPDATE SET
                external_evse_id=excluded.external_evse_id,status=excluded.status,source_last_updated=excluded.source_last_updated
                RETURNING id
                """, locationId, uid, Db(Optional(evse, "evse_id")), Required(evse, "status"),
                Timestamp(Optional(evse, "last_updated")));
            countEvse++;
            foreach (var connector in Array(evse, "connectors"))
            {
                var connectorSourceId = Required(connector, "id");
                if (!seenConnectors.Add($"{evseKey}|{connectorSourceId}"))
                    throw new InvalidDataException($"Duplicate connector {evseKey}|{connectorSourceId}");
                var power = connector.TryGetProperty("max_electric_power", out var p) && p.ValueKind == JsonValueKind.Number
                    ? p.GetInt32() : (int?)null;
                var connectorId = await Upsert(connection, tx, """
                    INSERT INTO connector(evse_id,source_id,standard,power_type,max_electric_power)
                    VALUES($1,$2,$3,$4,$5)
                    ON CONFLICT(evse_id,source_id) DO UPDATE SET
                    standard=excluded.standard,power_type=excluded.power_type,max_electric_power=excluded.max_electric_power
                    RETURNING id
                    """, evseId, connectorSourceId, Db(Optional(connector, "standard")),
                    Db(Optional(connector, "power_type")), power is null ? DBNull.Value : power.Value);
                await Execute(connection, tx, "DELETE FROM connector_tariff WHERE connector_id=$1", connectorId);
                foreach (var tariffRef in Array(connector, "tariff_ids"))
                {
                    var tariffId = tariffRef.GetString();
                    if (string.IsNullOrWhiteSpace(tariffId)) continue;
                    await Execute(connection, tx, """
                        INSERT INTO connector_tariff(connector_id,tariff_country_code,tariff_party_id,tariff_source_id)
                        VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING
                        """, connectorId, country, party, tariffId);
                }
                countConnectors++;
            }
        }
    }
    if (countLoc < minimum) throw new InvalidDataException($"Only {countLoc} locations; expected >= {minimum}");
    if (tariffsPath is not null)
    {
        await foreach (var tariff in ReadGzip(tariffsPath))
        {
            var country = Required(tariff, "country_code");
            var party = Required(tariff, "party_id");
            var sourceId = Required(tariff, "id");
            if (!seenTariffs.Add($"{country}|{party}|{sourceId}"))
                throw new InvalidDataException($"Duplicate tariff {country}/{party}/{sourceId}");
            await Execute(connection, tx, """
                INSERT INTO tariff(country_code,party_id,source_id,currency,details,source_last_updated)
                VALUES($1,$2,$3,$4,$5::jsonb,$6)
                ON CONFLICT(country_code,party_id,source_id) DO UPDATE SET
                currency=excluded.currency,details=excluded.details,source_last_updated=excluded.source_last_updated
                """, country, party, sourceId, Db(Optional(tariff, "currency")), tariff.GetRawText(),
                Timestamp(Optional(tariff, "last_updated")));
        }
    }
    await Execute(connection, tx, """
        INSERT INTO import_run(source_name,source_sha256,status,completed_at,location_count,evse_count,connector_count)
        VALUES($1,$2,'completed',now(),$3,$4,$5)
        """, "ndw-ocpi", Sha(locationsPath), countLoc, countEvse, countConnectors);
    await tx.CommitAsync();
    Console.WriteLine($"Committed: {countLoc} locations, {countEvse} EVSEs, {countConnectors} connectors, {seenTariffs.Count} tariffs.");
    return 0;
}
catch (Exception e)
{
    await tx.RollbackAsync();
    Console.Error.WriteLine($"No changes published: {e.Message}");
    return 1;
}
