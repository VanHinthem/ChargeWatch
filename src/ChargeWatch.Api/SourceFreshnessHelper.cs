internal static class SourceFreshnessHelper
{
    // Describes age of the source record, NOT confidence or feed health.
    public static long? AgeSeconds(DateTimeOffset? updatedAt, DateTimeOffset? now = null)
    {
        if (updatedAt is null) return null;
        var seconds = ((now ?? DateTimeOffset.UtcNow) - updatedAt.Value).TotalSeconds;
        if (!double.IsFinite(seconds) || seconds < 0 || seconds > long.MaxValue) return null;
        return (long)Math.Floor(seconds);
    }

    public static string Classify(DateTimeOffset? updatedAt, DateTimeOffset? now = null)
    {
        var age = AgeSeconds(updatedAt, now);
        if (age is null) return "UNKNOWN";
        if (age <= 86400) return "RECENT";
        if (age <= 7L * 86400) return "AGING";
        if (age <= 30L * 86400) return "OLD";
        return "VERY_OLD";
    }

    public static void SelfTest()
    {
        var now = new DateTimeOffset(2026, 10, 28, 20, 0, 0, TimeSpan.Zero);
        static void Check(bool condition) { if (!condition) throw new InvalidOperationException("Source freshness selftest failed."); }
        Check(AgeSeconds(null, now) is null && Classify(null, now) == "UNKNOWN");
        Check(Classify(now.AddSeconds(1), now) == "UNKNOWN");
        Check(Classify(now.AddHours(-24), now) == "RECENT");
        Check(Classify(now.AddHours(-24).AddSeconds(-1), now) == "AGING");
        Check(Classify(now.AddDays(-7), now) == "AGING");
        Check(Classify(now.AddDays(-7).AddSeconds(-1), now) == "OLD");
        Check(Classify(now.AddDays(-30), now) == "OLD");
        Check(Classify(now.AddDays(-30).AddSeconds(-1), now) == "VERY_OLD");
        Check(Classify(new DateTimeOffset(2026, 9, 14, 14, 0, 0, TimeSpan.Zero), now) == "VERY_OLD");
    }
}
