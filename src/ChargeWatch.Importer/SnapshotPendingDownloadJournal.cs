using System.Security.Cryptography;
using System.Text.Json;

// One recoverable Locations 200 response; no per-poll historical records.
// The pending gzip is kept until the import transaction and cache promotion finish.
internal static class SnapshotPendingDownloadJournal
{
    private sealed record Entry(string Filename, string Sha256, DateTimeOffset ObservedAtUtc,
        string? ETag, DateTimeOffset? LastModified);

    public static async Task Save(string targetPath, string pendingPath, DateTimeOffset observedAt,
        string? etag, DateTimeOffset? lastModified)
    {
        var fullTarget = Path.GetFullPath(targetPath);
        var fullPending = Path.GetFullPath(pendingPath);
        if (Path.GetDirectoryName(fullPending) != Path.GetDirectoryName(fullTarget) ||
            !Path.GetFileName(fullPending).StartsWith(Path.GetFileName(fullTarget) + ".pending-", StringComparison.Ordinal))
            throw new InvalidOperationException("Pending gzip must reside beside the expected cache.");
        await using var data = File.OpenRead(fullPending);
        var hash = Convert.ToHexString(await SHA256.HashDataAsync(data)).ToLowerInvariant();
        var journal = targetPath + ".pending-response.json";
        var temporary = journal + ".tmp";
        var entry = new Entry(Path.GetFileName(fullPending), hash, observedAt.ToUniversalTime(), etag, lastModified);
        // Persist metadata bytes before atomic same-directory rename. The gzip
        // was flushed before Save was called, so metadata never points to an
        // incompletely copied response after an ordinary process crash.
        await using (var output = new FileStream(temporary, FileMode.Create,
            FileAccess.Write, FileShare.None, 4096, useAsync: true))
        {
            await JsonSerializer.SerializeAsync(output, entry);
            await output.FlushAsync();
            output.Flush(flushToDisk: true);
        }
        File.Move(temporary, journal, true);
    }

    public static async Task<(string Path, DateTimeOffset ObservedAtUtc, string? ETag,
        DateTimeOffset? LastModified)?> Load(string targetPath)
    {
        var journal = targetPath + ".pending-response.json";
        if (!File.Exists(journal)) return null;
        var entry = JsonSerializer.Deserialize<Entry>(await File.ReadAllTextAsync(journal))
            ?? throw new InvalidDataException("Invalid pending Locations response journal.");
        if (Path.GetFileName(entry.Filename) != entry.Filename ||
            !entry.Filename.StartsWith(Path.GetFileName(targetPath) + ".pending-", StringComparison.Ordinal))
            throw new InvalidDataException("Unexpected pending Locations filename.");
        var path = Path.Combine(Path.GetDirectoryName(Path.GetFullPath(targetPath))!, entry.Filename);
        if (!File.Exists(path))
        {
            // A process may die after cache promotion but before journal cleanup.
            // Treat that case as completed only when the promoted cache matches
            // exactly the originally verified response; never invent a new poll.
            if (File.Exists(targetPath))
            {
                await using var promoted = File.OpenRead(targetPath);
                var promotedHash = Convert.ToHexString(await SHA256.HashDataAsync(promoted)).ToLowerInvariant();
                if (promotedHash.Equals(entry.Sha256, StringComparison.OrdinalIgnoreCase))
                {
                    Complete(targetPath);
                    return null;
                }
            }
            throw new InvalidDataException("Pending Locations gzip is missing.");
        }
        await using var stream = File.OpenRead(path);
        var hash = Convert.ToHexString(await SHA256.HashDataAsync(stream)).ToLowerInvariant();
        if (!hash.Equals(entry.Sha256, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Pending Locations gzip has changed.");
        return (path, entry.ObservedAtUtc, entry.ETag, entry.LastModified);
    }

    public static void Complete(string targetPath)
    {
        File.Delete(targetPath + ".pending-response.json");
    }

    public static async Task SelfTest()
    {
        var directory = Path.Combine(Path.GetTempPath(), "cw-pending-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var target = Path.Combine(directory, "locations.json.gz");
            var pending = target + ".pending-one";
            await File.WriteAllTextAsync(pending, "test pending body");
            var when = new DateTimeOffset(2026, 10, 24, 8, 12, 3, TimeSpan.Zero);
            await Save(target, pending, when, "\"etag\"", null);
            // Simulate a crash before the metadata rename. An orphan .tmp
            // must never be mistaken for a committed response.
            await File.WriteAllTextAsync(target + ".pending-response.json.tmp", "{broken");
            var read = await Load(target);
            if (read is null || read.Value.Path != pending || read.Value.ObservedAtUtc != when)
                throw new Exception("Pending response did not survive new journal reader.");
            await File.AppendAllTextAsync(pending, "corrupt");
            var rejected = false;
            try { await Load(target); } catch (InvalidDataException) { rejected = true; }
            if (!rejected) throw new Exception("Tampered pending response was accepted.");
            // Simulate process termination after File.Move(pending, target),
            // before removing the pending response journal.
            await File.WriteAllTextAsync(pending, "test pending body");
            await Save(target, pending, when, "\"etag\"", null);
            File.Move(pending, target, true);
            if (await Load(target) is not null)
                throw new Exception("Already promoted response was replayed.");
            if (File.Exists(target + ".pending-response.json"))
                throw new Exception("Already promoted journal not cleaned.");
            Complete(target);
            if (await Load(target) is not null)
                throw new Exception("Completed response was replayed.");
        }
        finally { Directory.Delete(directory, true); }
    }
}
