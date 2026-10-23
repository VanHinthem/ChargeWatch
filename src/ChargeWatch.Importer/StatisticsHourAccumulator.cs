// Pure accumulator for one real UTC hour; reusable for EVSE and location samples.
// No persistence until source freshness and coverage are established.
internal sealed class StatisticsHourAccumulator
{
    private readonly DateTimeOffset _hourStart;
    private readonly long _minimumSeconds;
    private long _coveredSeconds;
    private long _positiveSeconds;
    private DateTimeOffset? _lastEnd;

    public StatisticsHourAccumulator(DateTimeOffset hourStartUtc, long minimumSeconds = 2700)
    {
        if (hourStartUtc.Offset != TimeSpan.Zero || hourStartUtc.Minute != 0 ||
            hourStartUtc.Second != 0 || hourStartUtc.Millisecond != 0)
            throw new ArgumentException("Start must be an exact UTC hour.", nameof(hourStartUtc));
        if (minimumSeconds is < 1 or > 3600)
            throw new ArgumentOutOfRangeException(nameof(minimumSeconds));
        _hourStart = hourStartUtc;
        _minimumSeconds = minimumSeconds;
    }

    public long CoveredSeconds => _coveredSeconds;

    public void Add(DateTimeOffset startUtc, DateTimeOffset endUtc, bool positive)
    {
        if (startUtc.Offset != TimeSpan.Zero || endUtc.Offset != TimeSpan.Zero ||
            startUtc < _hourStart || endUtc > _hourStart.AddHours(1) || endUtc <= startUtc)
            throw new ArgumentOutOfRangeException(nameof(startUtc), "Interval must be inside the UTC hour.");

        if (_lastEnd is not null && startUtc < _lastEnd.Value)
            throw new InvalidOperationException("Overlapping intervals are not allowed.");
        var seconds = (long)(endUtc - startUtc).TotalSeconds;
        if (seconds <= 0 || _coveredSeconds + seconds > 3600)
            throw new InvalidOperationException("Invalid or excessive interval coverage.");
        _coveredSeconds += seconds;
        _lastEnd = endUtc;
        if (positive) _positiveSeconds += seconds;
    }

    public bool TryComplete(out double fraction)
    {
        fraction = 0;
        if (_coveredSeconds < _minimumSeconds) return false;
        fraction = (double)_positiveSeconds / _coveredSeconds;
        return true;
    }

    public static double UpdateEwma(double? previous, double sample, double alpha = 0.20)
    {
        if (sample is < 0 or > 1 || double.IsNaN(sample) || alpha is <= 0 or > 1)
            throw new ArgumentOutOfRangeException(nameof(sample));
        return previous is null ? sample : alpha * sample + (1 - alpha) * previous.Value;
    }

    public static int SelfTest()
    {
        static DateTimeOffset T(string s) => DateTimeOffset.Parse(s, System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.AdjustToUniversal);
        static void Require(bool value, string caseName)
        {
            if (!value) throw new InvalidOperationException("Accumulator failed: " + caseName);
        }
        var h = new StatisticsHourAccumulator(T("2026-10-23T08:00:00Z"));
        h.Add(T("2026-10-23T08:00:00Z"), T("2026-10-23T08:10:00Z"), true);
        Require(!h.TryComplete(out _), "ten-minute sample must not publish");
        h.Add(T("2026-10-23T08:10:00Z"), T("2026-10-23T08:50:00Z"), false);
        Require(h.TryComplete(out var fraction) && Math.Abs(fraction - 0.2) < 0.000001,
            "50-minute weighted sample");
        Require(Math.Abs(UpdateEwma(0.5, fraction) - 0.44) < 0.000001, "EWMA alpha");
        var empty = new StatisticsHourAccumulator(T("2026-10-23T09:00:00Z"));
        Require(!empty.TryComplete(out _), "empty hour is insufficient data");
        Console.WriteLine("PASS: hourly coverage gate, weighted sample and EWMA.");
        return 0;
    }
}
