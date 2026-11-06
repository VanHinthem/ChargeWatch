// Historical observed NDW status share from compact EVSE weekday/hour profiles.
// This describes an average observed week, NOT rolling 90-day measured uptime.
internal sealed class ReliabilityAccumulator
{
    private double _operational;
    private double _fault;
    private double _unknown;
    private int _coveredSlots;

    public void AddWeekday(float?[] categories, int[] samples)
    {
        if (categories.Length != 9 * 24 || samples.Length != 24)
            throw new InvalidDataException("Unexpected EVSE reliability profile array shape.");

        for (int hour = 0; hour < 24; hour++)
        {
            if (samples[hour] <= 0) continue;

            Span<double> values = stackalloc double[9];
            double sum = 0;
            bool valid = true;
            for (int category = 0; category < 9; category++)
            {
                float? source = categories[category * 24 + hour];
                if (source is null || !float.IsFinite(source.Value)
                    || source.Value < 0f || source.Value > 1f)
                {
                    valid = false;
                    break;
                }
                values[category] = source.Value;
                sum += source.Value;
            }

            if (!valid || Math.Abs(sum - 1.0) > 0.002) continue;

            double operational = values[0] + values[1] + values[2] + values[3];
            double fault = values[4] + values[5];
            double unknown = values[6];
            double active = operational + fault + unknown;

            // PLANNED and REMOVED have no active-capacity observation.
            if (active <= 0) continue;

            // Every covered weekday/hour/EVSE contributes one weight, not
            // one weight per five-minute poll.
            _operational += operational / active;
            _fault += fault / active;
            _unknown += unknown / active;
            _coveredSlots++;
        }
    }

    public ReliabilitySummary Summary(int totalSlots)
    {
        if (_coveredSlots == 0)
            return new ReliabilitySummary(null, null, null, 0, totalSlots);

        double denominator = _operational + _fault + _unknown;
        return new ReliabilitySummary(
            Math.Round(100 * _operational / denominator, 1),
            Math.Round(100 * _fault / denominator, 1),
            Math.Round(100 * _unknown / denominator, 1),
            _coveredSlots, totalSlots);
    }

    public static void SelfTest()
    {
        static void Require(bool condition, string message)
        {
            if (!condition) throw new InvalidOperationException("Reliability selftest: " + message);
        }

        static float?[] Categories(params (int category, float share)[] values)
        {
            var result = new float?[9 * 24];
            for (int category = 0; category < 9; category++)
                result[category * 24] = 0f;
            foreach (var (category, share) in values)
                result[category * 24] = share;
            return result;
        }

        var samples = new int[24];
        samples[0] = 12;
        var allOperational = new ReliabilityAccumulator();
        allOperational.AddWeekday(Categories((0, 0.3f), (1, 0.7f)), samples);
        var normal = allOperational.Summary(168);
        Require(normal.CoveredSlots == 1 && normal.OperationalPercent == 100
                && normal.FaultPercent == 0 && normal.UnknownPercent == 0,
            "available and occupied must both be operational");

        var mixed = new ReliabilityAccumulator();
        mixed.AddWeekday(Categories((0, 0.5f), (4, 0.25f), (6, 0.25f)), samples);
        var mix = mixed.Summary(168);
        Require(mix.OperationalPercent == 50 && mix.FaultPercent == 25
                && mix.UnknownPercent == 25, "fault/unknown must remain separate");

        var allUnknown = new ReliabilityAccumulator();
        allUnknown.AddWeekday(Categories((6, 1f)), samples);
        Require(allUnknown.Summary(168).UnknownPercent == 100, "unknown-only");

        var excluded = new ReliabilityAccumulator();
        excluded.AddWeekday(Categories((7, 1f)), samples);
        Require(excluded.Summary(168).OperationalPercent is null, "planned-only has no score");

        var empty = new ReliabilityAccumulator();
        empty.AddWeekday(Categories((4, 1f)), new int[24]);
        Require(empty.Summary(168).CoveredSlots == 0, "zero samples are not evidence");

        var invalid = new ReliabilityAccumulator();
        invalid.AddWeekday(Categories((0, 0.4f), (4, 0.4f)), samples);
        Require(invalid.Summary(168).CoveredSlots == 0, "invalid category sum must be excluded");

        var two = new ReliabilityAccumulator();
        two.AddWeekday(Categories((0, 1f)), samples);
        var otherDay = new int[24];
        otherDay[0] = 1;
        two.AddWeekday(Categories((4, 1f)), otherDay);
        Require(two.Summary(168).OperationalPercent == 50
                && two.Summary(168).FaultPercent == 50,
            "each covered hour weighs equally, regardless of poll count");
    }
}
