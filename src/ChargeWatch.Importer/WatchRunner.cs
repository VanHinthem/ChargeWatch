using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;

internal static class WatchRunner
{
    private const string DefaultLocationsUrl = "https://opendata.ndw.nu/charging_point_locations_ocpi.json.gz";
    private const string DefaultTariffsUrl = "https://opendata.ndw.nu/charging_point_tariffs_ocpi.json.gz";

    private sealed record FeedState(string? ETag = null, DateTimeOffset? LastModified = null, DateTimeOffset? LastSuccessfulCheckUtc = null);
    private sealed record RefreshState(
        FeedState? Locations = null,
        FeedState? Tariffs = null,
        DateTimeOffset? LastSuccessfulCheckUtc = null,
        DateTimeOffset? LastSuccessfulImportUtc = null);

    private sealed record DownloadResult(
        bool Changed,
        string? PendingPath,
        string? ETag,
        DateTimeOffset? LastModified,
        DateTimeOffset? FetchedAtUtc);

    public static Task<int> RunOnce()
        => Run(oneShot: true);

    public static Task<int> RunContinuous()
        => Run(oneShot: false);

    private static async Task<int> Run(bool oneShot)
    {
        var connection = Environment.GetEnvironmentVariable("CW_IMPORT_CONNECTION");
        if (string.IsNullOrWhiteSpace(connection))
        {
            Console.Error.WriteLine("Set CW_IMPORT_CONNECTION.");
            return 2;
        }

        var cacheDirectory = Environment.GetEnvironmentVariable("CW_NDW_CACHE_DIR");
        if (string.IsNullOrWhiteSpace(cacheDirectory))
            cacheDirectory = Path.Combine(AppContext.BaseDirectory, "ndw-cache");
        Directory.CreateDirectory(cacheDirectory);

        var locationsUrl = Environment.GetEnvironmentVariable("CW_NDW_LOCATIONS_URL") ?? DefaultLocationsUrl;
        var tariffsUrl = Environment.GetEnvironmentVariable("CW_NDW_TARIFFS_URL") ?? DefaultTariffsUrl;
        var locationInterval = ReadMinutes("CW_NDW_LOCATIONS_INTERVAL_MINUTES", 5);
        var tariffInterval = ReadMinutes("CW_NDW_TARIFFS_INTERVAL_MINUTES", 60);
        var staleAfter = ReadMinutes("CW_NDW_STALE_AFTER_MINUTES", 15);
        var minimumLocations = ReadPositiveInt("CW_NDW_MIN_LOCATIONS", 50_000);
        var timeoutSeconds = ReadPositiveInt("CW_NDW_HTTP_TIMEOUT_SECONDS", 120);

        var locationsPath = Path.Combine(cacheDirectory, "locations.json.gz");
        var tariffsPath = Path.Combine(cacheDirectory, "tariffs.json.gz");
        var statePath = Path.Combine(cacheDirectory, "refresh-state.json");
        var state = await LoadState(statePath);

        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(timeoutSeconds) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("ChargeWatch/1.0");

        using var stop = new CancellationTokenSource();
        Console.CancelKeyPress += (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            stop.Cancel();
        };

        var nextLocations = DateTimeOffset.MinValue;
        var nextTariffs = DateTimeOffset.MinValue;

        do
        {
            var now = DateTimeOffset.UtcNow;
            var checkLocations = oneShot || now >= nextLocations;
            var checkTariffs = oneShot || now >= nextTariffs;

            if (checkLocations)
                nextLocations = now + locationInterval;
            if (checkTariffs)
                nextTariffs = now + tariffInterval;

            try
            {
                state = await Refresh(
                    http,
                    state,
                    statePath,
                    locationsUrl,
                    tariffsUrl,
                    locationsPath,
                    tariffsPath,
                    minimumLocations,
                    checkLocations,
                    checkTariffs,
                    stop.Token);
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"Refresh failed; previous cache/database retained: {ex.Message}");
                if (oneShot)
                    return 1;
            }

            ReportStaleness(state, staleAfter);

            if (oneShot)
                break;

            var due = nextLocations < nextTariffs ? nextLocations : nextTariffs;
            var delay = due - DateTimeOffset.UtcNow;
            if (delay < TimeSpan.FromSeconds(1))
                delay = TimeSpan.FromSeconds(1);

            try
            {
                await Task.Delay(delay, stop.Token);
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested)
            {
                break;
            }
        }
        while (!stop.IsCancellationRequested);

        return 0;
    }

    private static async Task<RefreshState> Refresh(
        HttpClient http,
        RefreshState state,
        string statePath,
        string locationsUrl,
        string tariffsUrl,
        string locationsPath,
        string tariffsPath,
        int minimumLocations,
        bool checkLocations,
        bool checkTariffs,
        CancellationToken cancellationToken)
    {
        DownloadResult? locations = null;
        DownloadResult? tariffs = null;

        try
        {
            if (checkLocations)
                locations = await Download(http, locationsUrl, state.Locations, locationsPath, cancellationToken);
            if (checkTariffs)
                tariffs = await Download(http, tariffsUrl, state.Tariffs, tariffsPath, cancellationToken);

            var locationsForImport = locations?.Changed == true ? locations.PendingPath! : locationsPath;
            var tariffsForImport = tariffs?.Changed == true ? tariffs.PendingPath! : tariffsPath;
            var anyChanged = locations?.Changed == true || tariffs?.Changed == true;

            if (!File.Exists(locationsForImport))
                throw new InvalidOperationException("No valid Locations cache exists yet.");
            if (!File.Exists(tariffsForImport))
                throw new InvalidOperationException("No valid Tariffs cache exists yet.");

            if (anyChanged)
            {
                Console.WriteLine("NDW source changed; running transactional bulk import.");
                var result = await BulkImporter.Run([
                    locationsForImport,
                    tariffsForImport,
                    minimumLocations.ToString(System.Globalization.CultureInfo.InvariantCulture)
                ], locations?.Changed == true ? locations.FetchedAtUtc : null);
                if (result != 0)
                    throw new InvalidOperationException($"Bulk importer returned exit code {result}.");

                if (locations?.Changed == true)
                    Promote(locations.PendingPath!, locationsPath);
                if (tariffs?.Changed == true)
                    Promote(tariffs.PendingPath!, tariffsPath);
            }
            else
            {
                Console.WriteLine("NDW source unchanged (304); database import skipped.");
            }

            // PR17 opt-in statistics. A real new 304 response is a fresh
            // observed-status sample despite identical source bytes.
            // Tariff-only imports do not themselves produce Locations samples.
            if (locations is { Changed: false, FetchedAtUtc: { } observedAt } &&
                Environment.GetEnvironmentVariable("CW_ENABLE_COMPACT_STATISTICS") == "1")
            {
                var conn = Environment.GetEnvironmentVariable("CW_IMPORT_CONNECTION")
                    ?? throw new InvalidOperationException("CW_IMPORT_CONNECTION missing.");
                string cacheSha256;
                await using (var cached = File.OpenRead(locationsPath))
                    cacheSha256 = Convert.ToHexString(SHA256.HashData(cached)).ToLowerInvariant();
                var accepted = await SnapshotUnchangedPollRecorder.Record(
                    conn, Guid.NewGuid().ToString("N"), observedAt, cacheSha256);
                Console.WriteLine(accepted
                    ? "Compact snapshot: verified 304 observation stored."
                    : "Compact snapshot: duplicate observation skipped.");
            }

            var newState = state with
            {
                Locations = locations is null
                    ? state.Locations
                    : new FeedState(locations.ETag ?? state.Locations?.ETag, locations.LastModified ?? state.Locations?.LastModified, DateTimeOffset.UtcNow),
                Tariffs = tariffs is null
                    ? state.Tariffs
                    : new FeedState(tariffs.ETag ?? state.Tariffs?.ETag, tariffs.LastModified ?? state.Tariffs?.LastModified, DateTimeOffset.UtcNow),
                LastSuccessfulCheckUtc = DateTimeOffset.UtcNow,
                LastSuccessfulImportUtc = anyChanged ? DateTimeOffset.UtcNow : state.LastSuccessfulImportUtc
            };
            await SaveState(statePath, newState);
            return newState;
        }
        finally
        {
            DeletePending(locations?.PendingPath);
            DeletePending(tariffs?.PendingPath);
        }
    }


    // Local in-process HTTP contract test: never requests the NDW production feed.
    internal static async Task SelfTestHttp()
    {
        var folder = Path.Combine(Path.GetTempPath(), "chargewatch-http-selftest-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        var cached = Path.Combine(folder, "locations.json.gz");
        try
        {
            byte[] validGzip;
            await using (var memory = new MemoryStream())
            {
                await using (var compressor = new GZipStream(memory, CompressionLevel.Fastest, leaveOpen: true))
                    await compressor.WriteAsync(System.Text.Encoding.UTF8.GetBytes("[{}]"));
                validGzip = memory.ToArray();
            }

            var calls = 0;
            using var http = new HttpClient(new ContractHandler(request =>
            {
                calls++;
                if (calls == 1)
                {
                    var response = new HttpResponseMessage(HttpStatusCode.OK)
                    { Content = new ByteArrayContent(validGzip) };
                    response.Headers.ETag = new EntityTagHeaderValue("\"feed-v1\"");
                    return response;
                }
                if (calls == 2)
                {
                    if (!request.Headers.IfNoneMatch.Any(x => x.Tag == "\"feed-v1\""))
                        throw new InvalidOperationException("HTTP selftest: missing conditional ETag");
                    return new HttpResponseMessage(HttpStatusCode.NotModified);
                }
                return new HttpResponseMessage(HttpStatusCode.OK)
                { Content = new ByteArrayContent(System.Text.Encoding.UTF8.GetBytes("invalid gzip")) };
            }));
            var first = await Download(http, "https://example.invalid/locations", null, cached, CancellationToken.None);
            if (!first.Changed || first.PendingPath is null || first.FetchedAtUtc is null)
                throw new InvalidOperationException("HTTP selftest: first 200 did not return verified pending content");
            Promote(first.PendingPath, cached);
            var firstBytes = await File.ReadAllBytesAsync(cached);
            var firstSha = Convert.ToHexString(SHA256.HashData(firstBytes)).ToLowerInvariant();
            if (firstSha.Length != 64 || firstSha == new string('0',64))
                throw new InvalidOperationException("HTTP selftest: cache fingerprint missing");
            var unchanged = await Download(http, "https://example.invalid/locations",
                new FeedState(first.ETag), cached, CancellationToken.None);
            if (unchanged.Changed || unchanged.PendingPath is not null || unchanged.FetchedAtUtc is null)
                throw new InvalidOperationException("HTTP selftest: 304 did not count as distinct verified observation");
            var bytesAfter304 = await File.ReadAllBytesAsync(cached);
            if (!firstBytes.SequenceEqual(bytesAfter304))
                throw new InvalidOperationException("HTTP selftest: 304 modified cached snapshot");

            var invalidRejected = false;
            try { await Download(http, "https://example.invalid/locations", null, cached, CancellationToken.None); }
            catch (InvalidDataException) { invalidRejected = true; }
            var bytesAfterInvalid200 = await File.ReadAllBytesAsync(cached);
            if (!invalidRejected || !firstBytes.SequenceEqual(bytesAfterInvalid200))
                throw new InvalidOperationException("HTTP selftest: bad 200 changed the committed cache");
            if (Directory.GetFiles(folder, "*.pending-*").Length != 0)
                throw new InvalidOperationException("HTTP selftest: bad 200 left a pending file");
            Console.WriteLine("HTTP 200/304/cache contract selftest passed.");
        }
        finally { Directory.Delete(folder, recursive: true); }
    }

    private sealed class ContractHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(responder(request));
    }

    private static async Task<DownloadResult> Download(
        HttpClient http,
        string url,
        FeedState? state,
        string targetPath,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        if (!string.IsNullOrWhiteSpace(state?.ETag) && EntityTagHeaderValue.TryParse(state.ETag, out var tag))
            request.Headers.IfNoneMatch.Add(tag);
        if (state?.LastModified is not null)
            request.Headers.IfModifiedSince = state.LastModified;

        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (response.StatusCode == HttpStatusCode.NotModified)
        {
            if (!File.Exists(targetPath))
                throw new InvalidOperationException($"Source returned 304 but cache file is missing: {targetPath}");

            Console.WriteLine($"304 {url}");
            return new DownloadResult(false, null, state?.ETag, state?.LastModified, DateTimeOffset.UtcNow);
        }

        response.EnsureSuccessStatusCode();

        var pendingPath = targetPath + ".pending-" + Guid.NewGuid().ToString("N");
        try
        {
            await using (var output = new FileStream(pendingPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1024 * 128, useAsync: true))
                await response.Content.CopyToAsync(output, cancellationToken);

            await ValidateGzip(pendingPath, cancellationToken);

            var etag = response.Headers.ETag?.ToString();
            var lastModified = response.Content.Headers.LastModified;
            Console.WriteLine($"200 {url} -> {new FileInfo(pendingPath).Length:N0} bytes pending validation/import.");
            return new DownloadResult(true, pendingPath, etag, lastModified, DateTimeOffset.UtcNow);
        }
        catch
        {
            DeletePending(pendingPath);
            throw;
        }
    }

    private static async Task ValidateGzip(string path, CancellationToken cancellationToken)
    {
        await using var file = File.OpenRead(path);
        await using var gzip = new GZipStream(file, CompressionMode.Decompress);
        var buffer = new byte[1];
        var read = await gzip.ReadAsync(buffer, cancellationToken);
        if (read != 1)
            throw new InvalidDataException("Downloaded gzip is empty.");
    }

    private static void Promote(string pendingPath, string targetPath)
        => File.Move(pendingPath, targetPath, overwrite: true);

    private static void DeletePending(string? path)
    {
        if (!string.IsNullOrWhiteSpace(path) && File.Exists(path))
            File.Delete(path);
    }

    private static async Task<RefreshState> LoadState(string path)
    {
        if (!File.Exists(path))
            return new RefreshState();

        await using var file = File.OpenRead(path);
        return await JsonSerializer.DeserializeAsync<RefreshState>(file) ?? new RefreshState();
    }

    private static async Task SaveState(string path, RefreshState state)
    {
        var pending = path + ".tmp";
        await using (var file = File.Create(pending))
            await JsonSerializer.SerializeAsync(file, state, new JsonSerializerOptions { WriteIndented = true });
        File.Move(pending, path, overwrite: true);
    }

    private static TimeSpan ReadMinutes(string name, int fallback)
    {
        var value = ReadPositiveInt(name, fallback);
        return TimeSpan.FromMinutes(value);
    }

    private static int ReadPositiveInt(string name, int fallback)
    {
        var raw = Environment.GetEnvironmentVariable(name);
        if (string.IsNullOrWhiteSpace(raw))
            return fallback;
        if (!int.TryParse(raw, out var value) || value <= 0)
            throw new ArgumentException($"{name} must be a positive integer.");
        return value;
    }

    private static void ReportStaleness(RefreshState state, TimeSpan threshold)
    {
        var lastLocationsCheck = state.Locations?.LastSuccessfulCheckUtc;
        if (lastLocationsCheck is null)
        {
            Console.Error.WriteLine("STALE: no successful NDW Locations refresh check has completed yet.");
            return;
        }

        var age = DateTimeOffset.UtcNow - lastLocationsCheck.Value;
        if (age > threshold)
            Console.Error.WriteLine($"STALE: last successful NDW Locations refresh check was {age:g} ago (threshold {threshold:g}).");
    }
}
