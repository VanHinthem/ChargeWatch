// Pure one-hour reducer for the upcoming compact snapshot write path.
// This deliberately does NOT persist state or infer unobserved intervals.
internal sealed class SnapshotHourReducer<TCategory> where TCategory : struct, Enum
{
    private readonly Dictionary<TCategory, int> _counts = [];
    private readonly HashSet<string> _pollIds = new(StringComparer.Ordinal);
    private readonly DateTimeOffset _hourUtc;
    private int _changed;
    private int _unchanged;

    public SnapshotHourReducer(DateTimeOffset firstObservationUtc)
    {
        var utc = firstObservationUtc.ToUniversalTime();
        _hourUtc = new DateTimeOffset(utc.Year, utc.Month, utc.Day, utc.Hour, 0, 0, TimeSpan.Zero);
    }

    public DateTimeOffset HourStartUtc => _hourUtc;
    public int SampleCount => _counts.Values.Sum();
    public int ChangedCount => _changed;
    public int UnchangedCount => _unchanged;

    // A duplicate poll is rejected; replay idempotence across process restarts
    // must additionally be enforced by a transactional database checkpoint.
    public bool Observe(string pollId, DateTimeOffset observedAtUtc, TCategory category, SnapshotFeedQuality quality)
    {
        if (string.IsNullOrWhiteSpace(pollId))
            throw new ArgumentException("Poll ID is required.", nameof(pollId));
        var utc = observedAtUtc.ToUniversalTime();
        if (utc < _hourUtc || utc >= _hourUtc.AddHours(1))
            throw new ArgumentOutOfRangeException(nameof(observedAtUtc), "Observation is outside reducer UTC hour.");
        if (!_pollIds.Add(pollId))
            return false;
        _counts[category] = _counts.GetValueOrDefault(category) + 1;
        if (quality == SnapshotFeedQuality.ChangedFeed)
            _changed++;
        else
            _unchanged++;
        return true;
    }

    public SnapshotHourSample<TCategory> Complete(DateTimeOffset observationAtOrAfterEndUtc)
    {
        if (observationAtOrAfterEndUtc.ToUniversalTime() < _hourUtc.AddHours(1))
            throw new InvalidOperationException("Cannot complete an hour before its UTC end.");
        if (SampleCount == 0)
            throw new InvalidOperationException("Cannot complete an hour without observations.");
        var zone = TimeZoneInfo.FindSystemTimeZoneById("Europe/Amsterdam");
        var local = TimeZoneInfo.ConvertTime(_hourUtc, zone);
        var weekday = ((int)local.DayOfWeek + 6) % 7 + 1;
        var probabilities = _counts.ToDictionary(x => x.Key, x => (double)x.Value / SampleCount);
        return new SnapshotHourSample<TCategory>(
            _hourUtc, weekday, local.Hour, SampleCount, _changed, _unchanged, probabilities);
    }
}

internal sealed record SnapshotHourSample<TCategory>(
    DateTimeOffset HourStartUtc,
    int IsoWeekday,
    int LocalHour,
    int SampleCount,
    int ChangedCount,
    int UnchangedCount,
    IReadOnlyDictionary<TCategory, double> Probabilities) where TCategory : struct, Enum;

internal static class SnapshotHourReducerTests
{
    public static void SelfTest()
    {
        static void Check(bool condition, string reason)
        {
            if (!condition) throw new InvalidOperationException("Snapshot reducer selftest: " + reason);
        }

        var start = new DateTimeOffset(2026, 10, 24, 7, 0, 0, TimeSpan.Zero);
        var reducer = new SnapshotHourReducer<SnapshotEvseCategory>(start);
        Check(reducer.Observe("poll-1", start.AddMinutes(5), SnapshotEvseCategory.Available, SnapshotFeedQuality.ChangedFeed), "new 200");
        Check(!reducer.Observe("poll-1", start.AddMinutes(5), SnapshotEvseCategory.Charging, SnapshotFeedQuality.ChangedFeed), "replay must not change counts");
        Check(reducer.Observe("poll-2", start.AddMinutes(10), SnapshotEvseCategory.Charging, SnapshotFeedQuality.UnchangedFeed), "successful 304 counts");
        Check(reducer.SampleCount == 2 && reducer.ChangedCount == 1 && reducer.UnchangedCount == 1, "quality/counts");
        var result = reducer.Complete(start.AddHours(1));
        Check(result.IsoWeekday == 6 && result.LocalHour == 9, "Amsterdam local slot");
        Check(result.Probabilities[SnapshotEvseCategory.Available] == 0.5, "hourly category probability");
        var premature = new SnapshotHourReducer<SnapshotEvseCategory>(start);
        premature.Observe("poll-3", start, SnapshotEvseCategory.Available, SnapshotFeedQuality.ChangedFeed);
        try { premature.Complete(start.AddMinutes(59)); throw new InvalidOperationException("Should reject premature completion"); }
        catch (InvalidOperationException e) when (e.Message.StartsWith("Cannot complete")) { }

        // The repeated Dutch local winter hour represents two distinct UTC hours,
        // both mapping to Sunday 02:00, without merging their physical UTC intervals.
        var beforeFallback = new SnapshotHourReducer<SnapshotEvseCategory>(
            new DateTimeOffset(2026, 10, 25, 0, 0, 0, TimeSpan.Zero));
        var afterFallback = new SnapshotHourReducer<SnapshotEvseCategory>(
            new DateTimeOffset(2026, 10, 25, 1, 0, 0, TimeSpan.Zero));
        beforeFallback.Observe("a", beforeFallback.HourStartUtc, SnapshotEvseCategory.Available, SnapshotFeedQuality.ChangedFeed);
        afterFallback.Observe("b", afterFallback.HourStartUtc, SnapshotEvseCategory.Charging, SnapshotFeedQuality.ChangedFeed);
        var a = beforeFallback.Complete(beforeFallback.HourStartUtc.AddHours(1));
        var b = afterFallback.Complete(afterFallback.HourStartUtc.AddHours(1));
        Check(a.IsoWeekday == 7 && b.IsoWeekday == 7 && a.LocalHour == 2 && b.LocalHour == 2, "winter DST");
        Console.WriteLine("Snapshot hour reducer selftest passed.");
    }
}
