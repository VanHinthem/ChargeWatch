// Pure UTC-duration splitting into ISO weekday x local hour slots.
// Do not treat repeated clock hours as the same instant; their durations
// contribute to the same weekly bucket, with both actual intervals accounted.
internal readonly record struct HourSegment(int Slot, DateTimeOffset StartUtc, DateTimeOffset EndUtc)
{
    public long Seconds => (long)(EndUtc - StartUtc).TotalSeconds;
}

internal static class StatisticsHourSplitter
{
    private static readonly TimeZoneInfo Dutch = TimeZoneInfo.FindSystemTimeZoneById("Europe/Amsterdam");

    public static int Slot(DateTimeOffset instant)
    {
        var local = TimeZoneInfo.ConvertTime(instant, Dutch);
        var mondayBasedDay = ((int)local.DayOfWeek + 6) % 7;
        return mondayBasedDay * 24 + local.Hour;
    }

    public static IReadOnlyList<HourSegment> Split(DateTimeOffset from, DateTimeOffset to)
    {
        if (from.Offset != TimeSpan.Zero || to.Offset != TimeSpan.Zero)
            throw new ArgumentException("Both input instants must use UTC offset +00:00.");
        if (to <= from || to - from > TimeSpan.FromMinutes(15))
            throw new ArgumentOutOfRangeException(nameof(to), "Require a positive interval of at most 15 minutes.");

        var pieces = new List<HourSegment>();
        var cursor = from;
        while (cursor < to)
        {
            // Search on the UTC timeline, not by constructing a local time that
            // might be nonexistent or ambiguous during a DST transition.
            var currentSlot = Slot(cursor);
            var remainingSeconds = (long)Math.Ceiling((to - cursor).TotalSeconds);
            var lo = 1L;
            var hi = remainingSeconds;
            // Find first UTC second with another local (weekday,hour) slot.
            // In a repeated autumn hour the slot stays 02; split at timezone
            // offset changes as well to retain the independent observations.
            var offset = Dutch.GetUtcOffset(cursor.UtcDateTime);
            bool Changed(long delta)
            {
                var next = cursor.AddSeconds(delta);
                return Slot(next) != currentSlot ||
                       Dutch.GetUtcOffset(next.UtcDateTime) != offset;
            }

            long advance;
            if (!Changed(hi))
                advance = hi;
            else
            {
                while (lo < hi)
                {
                    var mid = lo + (hi - lo) / 2;
                    if (Changed(mid)) hi = mid;
                    else lo = mid + 1;
                }
                advance = lo;
            }
            var end = cursor.AddSeconds(advance);
            pieces.Add(new HourSegment(currentSlot, cursor, end));
            cursor = end;
        }
        return pieces;
    }

    public static int SelfTest()
    {
        static DateTimeOffset Utc(string instant) => DateTimeOffset.Parse(instant, System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.AssumeUniversal | System.Globalization.DateTimeStyles.AdjustToUniversal);
        static void Check(bool condition, string label)
        {
            if (!condition) throw new InvalidOperationException("Hour splitter: " + label);
        }

        var midnight = Split(Utc("2026-10-24T21:55:00Z"), Utc("2026-10-24T22:05:00Z"));
        Check(midnight.Count == 2 && midnight[0].Slot == 5 * 24 + 23 &&
              midnight[1].Slot == 6 * 24 && midnight.Sum(x => x.Seconds) == 600,
              "Saturday-to-Sunday midnight");

        // Dutch autumn DST: 02:55 CEST -> 02:05 CET, same weekly slot but
        // genuinely two separate UTC intervals around the offset transition.
        var autumn = Split(Utc("2026-10-25T00:55:00Z"), Utc("2026-10-25T01:05:00Z"));
        Check(autumn.Count == 2 && autumn.All(x => x.Slot == 6 * 24 + 2) &&
              autumn.Sum(x => x.Seconds) == 600, "autumn repeated local hour");

        // Dutch spring DST: 01:55 CET -> 03:05 CEST; nonexistent 02:xx excluded.
        var spring = Split(Utc("2026-03-29T00:55:00Z"), Utc("2026-03-29T01:05:00Z"));
        Check(spring.Count == 2 && spring[0].Slot == 6 * 24 + 1 &&
              spring[1].Slot == 6 * 24 + 3 && spring.Sum(x => x.Seconds) == 600,
              "spring skipped local hour");

        var same = Split(Utc("2026-10-23T08:01:00Z"), Utc("2026-10-23T08:06:00Z"));
        Check(same.Count == 1 && same[0].Seconds == 300 && same[0].Slot == 4 * 24 + 10,
              "ordinary Friday interval");

        Console.WriteLine("PASS: 168-slot splitter midnight, summer DST, winter DST and normal hour.");
        return 0;
    }
}
