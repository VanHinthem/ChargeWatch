internal static class ConnectorPower
{
    internal readonly record struct EstimateResult(long? Watts, string Source);

    // OCPI: 3-phase voltage is specified line-to-neutral. Real source feeds can
    // nevertheless report nominal 400 V line-to-line; distinguish the assumption.
    public static EstimateResult Estimate(string? powerType, int? sourceWatts, int? voltage, int? amperage)
    {
        if (sourceWatts is > 0)
            return new EstimateResult(sourceWatts.Value, "source");

        if (voltage is null or <= 0 || amperage is null or <= 0)
            return new EstimateResult(null, "unknown");

        var v = (double)voltage.Value;
        var a = (double)amperage.Value;
        double watts;
        string provenance;
        switch (powerType)
        {
            case "AC_1_PHASE":
                watts = v * a;
                provenance = "derived_ac";
                break;
            case "AC_3_PHASE":
                // <= 300 V treated as line-neutral; above as line-line voltage.
                // An indicative calculation, never an explicit source rating.
                watts = (v <= 300 ? 3.0 : Math.Sqrt(3)) * v * a;
                provenance = "derived_ac";
                break;
            case "DC":
                // Independent maxima do not necessarily occur simultaneously.
                watts = v * a;
                provenance = "dc_upper_bound";
                break;
            default:
                return new EstimateResult(null, "unknown");
        }

        if (!double.IsFinite(watts) || watts <= 0 || watts > long.MaxValue)
            return new EstimateResult(null, "unknown");

        return new EstimateResult((long)Math.Round(watts, MidpointRounding.AwayFromZero), provenance);
    }
}
