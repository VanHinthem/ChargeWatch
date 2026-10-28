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

    private static string Csv(string json) => "\"" + json.Replace("\"", "\"\"") + "\"";

    public static async Task<int> Run(string[] args, DateTimeOffset? verifiedLocationsFetchedAtUtc = null)
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
            // CSV COPY preserves JSON backslashes and quotes; serializer escapes control characters.
            await using (var writer = await db.BeginTextImportAsync("COPY stage_loc(payload) FROM STDIN WITH (FORMAT csv)"))
            {
                await foreach (var loc in Read(locationsPath))
                {
                    var country = Required(loc, "country_code");
                    var party = Required(loc, "party_id");
                    var id = Required(loc, "id");
                    var key = Key(country, party, id);
                    if (!seenLocations.Add(key)) throw new InvalidDataException($"Duplicate location {key}");
                    locCount++;
                    await writer.WriteLineAsync(Csv(JsonSerializer.Serialize(new { country, party, id, data = loc })));
                }
            }
            if (locCount < minimum)
                throw new InvalidDataException($"Only {locCount} locations; expected >= {minimum}");

            // Re-read gzip to flatten EVSE/connector objects without retaining all locations in RAM.
            await using (var ew = await db.BeginTextImportAsync("COPY stage_evse(payload) FROM STDIN WITH (FORMAT csv)"))
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
                        await ew.WriteLineAsync(Csv(JsonSerializer.Serialize(new { country, party, id, uid, data = evse })));
                    }
                }
            }
            await using (var cw = await db.BeginTextImportAsync("COPY stage_connector(payload) FROM STDIN WITH (FORMAT csv)"))
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
                            await cw.WriteLineAsync(Csv(JsonSerializer.Serialize(new { country, party, id, uid, connectorId, data = connector })));
                        }
                    }
                }
            }

            if (tariffsPath is not null)
            {
                await using var tw = await db.BeginTextImportAsync("COPY stage_tariff(payload) FROM STDIN WITH (FORMAT csv)");
                await foreach (var tariff in Read(tariffsPath))
                {
                    var country = Required(tariff, "country_code");
                    var party = Required(tariff, "party_id");
                    var id = Required(tariff, "id");
                    if (!seenTariffs.Add(Key(country, party, id)))
                        throw new InvalidDataException($"Duplicate tariff {id}");
                    await tw.WriteLineAsync(Csv(JsonSerializer.Serialize(new { country, party, id, data = tariff })));
                }
            }

            // PR17 opt-in isolated integration gate; default remains disabled.
            // This runs while the OLD committed catalog is still intact and uses
            // the same transaction as the forthcoming location/EVSE upserts.
            if (verifiedLocationsFetchedAtUtc is { } verifiedAt &&
                Environment.GetEnvironmentVariable("CW_ENABLE_COMPACT_STATISTICS") == "1")
            {
                await SnapshotCatalogTransition.PrepareBeforeChangedCatalog(db, tx, verifiedAt);
            }
            // Fail closed on suspicious national feed shrinkage before any catalog upsert.
            if (verifiedLocationsFetchedAtUtc is not null)
            {
                await using var countCmd = new NpgsqlCommand(
                    "SELECT count(*) FROM charging_location WHERE is_active", db, tx);
                var activeBefore = (long)(await countCmd.ExecuteScalarAsync())!;
                if (activeBefore >= minimum && locCount < activeBefore * 0.90)
                    throw new InvalidDataException(
                        $"Suspicious Locations snapshot shrinkage: {locCount} versus {activeBefore} active. Reconciliation refused.");
            }
            // A partial EVSE feed can silently remove capacity even when location count is stable.
            // Require both national count guards before touching any presence state.
            if (verifiedLocationsFetchedAtUtc is not null)
            {
                await using var evseBaseline = new NpgsqlCommand(
                    "SELECT evse_count FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations'", db, tx);
                var before = await evseBaseline.ExecuteScalarAsync();
                if (before is long previousEvses && previousEvses >= 10000 && evseCount < previousEvses * 0.90)
                    throw new InvalidDataException(
                        $"Suspicious EVSE snapshot shrinkage: {evseCount} versus {previousEvses}. Reconciliation refused.");
            }
            Console.WriteLine($"Staged: {locCount} locations, {evseCount} EVSEs, {connectorCount} connectors, {seenTariffs.Count} tariffs. Publishing...");
            await Exec(db, tx, """
                INSERT INTO charging_location(country_code,party_id,source_id,address,city,operator_name,latitude,longitude,position,source_last_updated,is_active,source_data)
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
                       NULLIF(payload->'data'->>'last_updated','')::timestamptz, true,
                       (payload->'data') - 'evses'
                FROM stage_loc WHERE true
                ON CONFLICT(country_code,party_id,source_id) DO UPDATE SET
                  address=excluded.address, city=excluded.city, operator_name=excluded.operator_name,
                  latitude=excluded.latitude, longitude=excluded.longitude, position=excluded.position,
                  source_last_updated=excluded.source_last_updated,
                  source_data=excluded.source_data
                WHERE (charging_location.address,charging_location.city,charging_location.operator_name,charging_location.latitude,charging_location.longitude,charging_location.position,charging_location.source_last_updated,charging_location.source_data)
                    IS DISTINCT FROM (excluded.address,excluded.city,excluded.operator_name,excluded.latitude,excluded.longitude,excluded.position,excluded.source_last_updated,excluded.source_data);
                """);
            if (verifiedLocationsFetchedAtUtc is not null)
            {
                await Exec(db, tx, """
                    UPDATE charging_location l SET is_active=true
                    WHERE NOT l.is_active
                      AND EXISTS (
                        SELECT 1 FROM stage_loc s
                        WHERE s.payload->>'country'=l.country_code
                          AND s.payload->>'party'=l.party_id
                          AND s.payload->>'id'=l.source_id
                      );
                    UPDATE charging_location l SET is_active=false
                    WHERE l.is_active
                      AND NOT EXISTS (
                        SELECT 1 FROM stage_loc s
                        WHERE s.payload->>'country'=l.country_code
                          AND s.payload->>'party'=l.party_id
                          AND s.payload->>'id'=l.source_id
                      );
                    """);
            }
            await Exec(db, tx, """
                INSERT INTO evse(location_id,source_uid,external_evse_id,status,source_last_updated,source_data)
                SELECT l.id, s.payload->>'uid', s.payload->'data'->>'evse_id',
                       s.payload->'data'->>'status',
                       NULLIF(s.payload->'data'->>'last_updated','')::timestamptz,
                       (s.payload->'data') - 'connectors'
                FROM stage_evse s
                JOIN charging_location l ON l.country_code=s.payload->>'country'
                      AND l.party_id=s.payload->>'party' AND l.source_id=s.payload->>'id'
                WHERE true
                ON CONFLICT(location_id,source_uid) DO UPDATE SET
                  external_evse_id=excluded.external_evse_id, status=excluded.status,
                  source_last_updated=excluded.source_last_updated,
                  source_data=excluded.source_data
                WHERE (evse.external_evse_id,evse.status,evse.source_last_updated,evse.source_data)
                    IS DISTINCT FROM (excluded.external_evse_id,excluded.status,excluded.source_last_updated,excluded.source_data);
                """);
            if (verifiedLocationsFetchedAtUtc is { } presenceAt)
            {
                // One complete verified HTTP 200 is one presence observation. Keep
                // historical EVSE rows; three independent missing snapshots plus
                // at least ten minutes are needed to mark a row inactive.
                await using var presence = new NpgsqlCommand("""
                    CREATE TEMP TABLE stage_present_evse_ids ON COMMIT DROP AS
                    SELECT e.id FROM stage_evse s
                    JOIN charging_location l
                      ON l.country_code=s.payload->>'country'
                     AND l.party_id=s.payload->>'party'
                     AND l.source_id=s.payload->>'id'
                    JOIN evse e ON e.location_id=l.id AND e.source_uid=s.payload->>'uid';
                    CREATE UNIQUE INDEX ON stage_present_evse_ids(id);

                    UPDATE evse e SET
                      is_active=true,
                      last_seen_at=$1,
                      first_missing_at=NULL,
                      consecutive_missing_snapshots=0
                    FROM stage_present_evse_ids seen
                    WHERE seen.id=e.id
                      AND (NOT e.is_active OR e.last_seen_at IS DISTINCT FROM $1
                           OR e.first_missing_at IS NOT NULL
                           OR e.consecutive_missing_snapshots<>0);

                    UPDATE evse e SET
                      first_missing_at=COALESCE(e.first_missing_at,$1),
                      consecutive_missing_snapshots=e.consecutive_missing_snapshots+1,
                      is_active=NOT (
                        e.consecutive_missing_snapshots+1>=3
                        AND $1-COALESCE(e.first_missing_at,$1)>=interval '10 minutes')
                    FROM charging_location l
                    WHERE e.location_id=l.id AND l.is_active
                      AND NOT EXISTS (SELECT 1 FROM stage_present_evse_ids seen WHERE seen.id=e.id)
                      AND (e.last_presence_checked_at IS NULL OR e.last_presence_checked_at<$1);
                    UPDATE evse SET last_presence_checked_at=$1
                      WHERE last_presence_checked_at IS NULL OR last_presence_checked_at<$1;
                    """, db, tx);
                foreach (var part in presence.CommandText.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
                {
                    await using var query = new NpgsqlCommand(part, db, tx) { CommandTimeout = 600 };
                    if (part.Contains("$1", StringComparison.Ordinal))
                        query.Parameters.AddWithValue(presenceAt.ToUniversalTime());
                    await query.ExecuteNonQueryAsync();
                }
            }
            await Exec(db, tx, """
                INSERT INTO connector(evse_id,source_id,standard,power_type,max_electric_power,source_data,format,max_voltage,max_amperage)
                SELECT e.id,s.payload->>'connectorId',s.payload->'data'->>'standard',
                       s.payload->'data'->>'power_type',
                       NULLIF(s.payload->'data'->>'max_electric_power','')::integer,
                       s.payload->'data',
                       s.payload->'data'->>'format',
                       NULLIF(s.payload->'data'->>'max_voltage','')::integer,
                       NULLIF(s.payload->'data'->>'max_amperage','')::integer
                FROM stage_connector s
                JOIN charging_location l ON l.country_code=s.payload->>'country'
                   AND l.party_id=s.payload->>'party' AND l.source_id=s.payload->>'id'
                JOIN evse e ON e.location_id=l.id AND e.source_uid=s.payload->>'uid'
                WHERE true
                ON CONFLICT(evse_id,source_id) DO UPDATE SET
                   standard=excluded.standard,power_type=excluded.power_type,
                   max_electric_power=excluded.max_electric_power,
                   source_data=excluded.source_data,
                   format=excluded.format,max_voltage=excluded.max_voltage,max_amperage=excluded.max_amperage
                WHERE (connector.standard,connector.power_type,connector.max_electric_power,connector.source_data,connector.format,connector.max_voltage,connector.max_amperage)
                    IS DISTINCT FROM (excluded.standard,excluded.power_type,excluded.max_electric_power,excluded.source_data,excluded.format,excluded.max_voltage,excluded.max_amperage);
                """);
            await Exec(db, tx, """
                CREATE TEMP TABLE stage_connector_id ON COMMIT DROP AS
                SELECT c.id AS connector_id
                FROM stage_connector s
                JOIN charging_location l ON l.country_code=s.payload->>'country'
                   AND l.party_id=s.payload->>'party' AND l.source_id=s.payload->>'id'
                JOIN evse e ON e.location_id=l.id AND e.source_uid=s.payload->>'uid'
                JOIN connector c ON c.evse_id=e.id AND c.source_id=s.payload->>'connectorId';

                CREATE TEMP TABLE stage_desired_tariff ON COMMIT DROP AS
                SELECT DISTINCT c.id AS connector_id,s.payload->>'country' AS tariff_country_code,
                       s.payload->>'party' AS tariff_party_id,t.tariff_id AS tariff_source_id
                FROM stage_connector s
                JOIN charging_location l ON l.country_code=s.payload->>'country'
                   AND l.party_id=s.payload->>'party' AND l.source_id=s.payload->>'id'
                JOIN evse e ON e.location_id=l.id AND e.source_uid=s.payload->>'uid'
                JOIN connector c ON c.evse_id=e.id AND c.source_id=s.payload->>'connectorId'
                CROSS JOIN LATERAL jsonb_array_elements_text(
                   CASE WHEN jsonb_typeof(s.payload->'data'->'tariff_ids')='array'
                        THEN s.payload->'data'->'tariff_ids' ELSE '[]'::jsonb END
                ) AS t(tariff_id)
                WHERE length(t.tariff_id)>0;

                DELETE FROM connector_tariff ct
                USING stage_connector_id ids
                WHERE ct.connector_id=ids.connector_id
                  AND NOT EXISTS (
                    SELECT 1 FROM stage_desired_tariff d
                    WHERE d.connector_id=ct.connector_id
                      AND d.tariff_country_code=ct.tariff_country_code
                      AND d.tariff_party_id=ct.tariff_party_id
                      AND d.tariff_source_id=ct.tariff_source_id
                  );

                INSERT INTO connector_tariff(connector_id,tariff_country_code,tariff_party_id,tariff_source_id)
                SELECT connector_id,tariff_country_code,tariff_party_id,tariff_source_id
                FROM stage_desired_tariff WHERE true
                ON CONFLICT DO NOTHING;
                """);
            if (tariffsPath is not null)
                await Exec(db, tx, """
                    INSERT INTO tariff(country_code,party_id,source_id,currency,details,source_last_updated)
                    SELECT payload->>'country',payload->>'party',payload->>'id',
                           payload->'data'->>'currency',payload->'data',
                           NULLIF(payload->'data'->>'last_updated','')::timestamptz
                    FROM stage_tariff WHERE true
                    ON CONFLICT(country_code,party_id,source_id) DO UPDATE SET
                      currency=excluded.currency,details=excluded.details,
                      source_last_updated=excluded.source_last_updated
                    WHERE (tariff.currency,tariff.details,tariff.source_last_updated)
                        IS DISTINCT FROM (excluded.currency,excluded.details,excluded.source_last_updated);
                    """);

            // Sprint 3 checkpoints share the catalogue transaction. A failure rolls
            // back both; the additive schema migration must be applied first.
            await ObservationCheckpoints.Capture(db, tx);
            // Provenance is only supplied by WatchRunner after a successful HTTP 200
            // Locations download. Manual --bulk, 304 and tariff-only imports never
            // manufacture new observed fetch times.
            if (verifiedLocationsFetchedAtUtc is { } fetchedAt)
            {
                string locationsHash;
                await using (var source = File.OpenRead(locationsPath))
                    locationsHash = Convert.ToHexString(SHA256.HashData(source)).ToLowerInvariant();
                await using var provenance = new NpgsqlCommand("""
                    INSERT INTO ndw_snapshot_checkpoint
                      (source_name,source_sha256,fetched_at,location_count,evse_count)
                    VALUES ('ndw-locations',$1,$2,$3,$4)
                    ON CONFLICT(source_name) DO UPDATE SET
                      source_sha256=excluded.source_sha256,
                      fetched_at=excluded.fetched_at,
                      last_committed_at=statement_timestamp(),
                      location_count=excluded.location_count,
                      evse_count=excluded.evse_count
                    WHERE ndw_snapshot_checkpoint.source_sha256<>excluded.source_sha256
                      AND excluded.fetched_at>ndw_snapshot_checkpoint.fetched_at;
                    """, db, tx);
                provenance.Parameters.AddWithValue(locationsHash);
                provenance.Parameters.AddWithValue(fetchedAt.ToUniversalTime());
                provenance.Parameters.AddWithValue(locCount);
                provenance.Parameters.AddWithValue(evseCount);
                await SnapshotPollCoverage.Capture(db, tx, fetchedAt, locationsHash);
                await provenance.ExecuteNonQueryAsync();
                if (Environment.GetEnvironmentVariable("CW_ENABLE_COMPACT_STATISTICS") == "1")
                {
                    // HTTP 200: the old 304 counts were consumed before catalog upserts.
                    // Register one fresh observation against the new committed-to-be catalog
                    // with the poll claim in the SAME transaction as import/provenance.
                    if (await SnapshotPollStore.TryClaim(db, tx,
                            "200-" + locationsHash + "-" + fetchedAt.ToUniversalTime().Ticks.ToString(System.Globalization.CultureInfo.InvariantCulture), fetchedAt,
                            SnapshotFeedQuality.ChangedFeed))
                    {
                        await using var sample = new NpgsqlCommand(
                            "SELECT * FROM snapshot_record_changed_hour($1)", db, tx)
                            { CommandTimeout = 600 };
                        sample.Parameters.AddWithValue(fetchedAt.ToUniversalTime());
                        await sample.ExecuteNonQueryAsync();
                    }
                }
            }


            await EvseHourFinalizer.Run(db, tx);
            await LocationHourFinalizer.Run(db, tx);

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
            // CI-only fault injection, gated by the opt-in statistics feature.
            // An explicit marker file ensures the parent kills this process
            // after all catalog and statistics writes, BEFORE PostgreSQL commit.
            if (Environment.GetEnvironmentVariable("CW_ENABLE_COMPACT_STATISTICS") == "1" &&
                Environment.GetEnvironmentVariable("CW_TEST_PAUSE_BEFORE_COMMIT_FILE") is { Length: > 0 } marker)
            {
                await File.WriteAllTextAsync(marker, "ready");
                await Task.Delay(Timeout.InfiniteTimeSpan);
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
