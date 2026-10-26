// Experimental interpretation of the first category of an Amsterdam-local
// weekday/hour compact snapshot EWMA. This is descriptive history, not a
// calibrated forecast; data quality must be shown alongside every value.
internal static class ExperimentalAvailability
{
    public static double? AvailableShare(float? availableCategory, int samples, int changedFeeds)
    {
        if (samples <= 0 || changedFeeds <= 0 || availableCategory is null ||
            !float.IsFinite(availableCategory.Value) ||
            availableCategory.Value < 0f || availableCategory.Value > 1f)
            return null;
        return Math.Round((double)availableCategory.Value, 4);
    }

    public static void SelfTest()
    {
        static void Require(bool ok) { if (!ok) throw new InvalidOperationException("Experimental availability selftest failed."); }
        Require(AvailableShare(0.625f, 12, 3) == 0.625);
        Require(AvailableShare(0f, 5, 1) == 0d);
        Require(AvailableShare(null, 5, 2) is null);
        Require(AvailableShare(0.5f, 0, 0) is null);
        Require(AvailableShare(0.5f, 8, 0) is null);
        Require(AvailableShare(float.NaN, 9, 3) is null);
        Require(AvailableShare(1.4f, 9, 3) is null);
    }
}
