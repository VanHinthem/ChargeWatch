// Compact snapshot statistics semantics for PR #17.
// No database writes: this keeps classification/EWMA rules independently testable
// before selecting the final national-scale storage layout.
internal enum SnapshotEvseCategory
{
    Available,
    Charging,
    Blocked,
    Reserved,
    Inoperative,
    OutOfOrder,
    Unknown,
    Planned,
    Removed
}

internal enum SnapshotLocationCategory
{
    AnyAvailable,
    NoFreeKnown,
    Uncertain,
    DefectOnly,
    NoActiveCapacity
}

internal enum SnapshotFeedQuality
{
    ChangedFeed,
    UnchangedFeed
}

internal static class CompactSnapshotStatistics
{
    // A week-slot receives one aggregate sample per completed occurrence.
    // With normal weekly recurrence, this alpha gives a 90-day half-life:
    // (1-alpha)^(90/7) = 0.5.
    public const double HalfLifeDays = 90.0;
    public static readonly double WeeklySlotAlpha =
        1.0 - Math.Pow(0.5, 7.0 / HalfLifeDays);

    public static SnapshotEvseCategory ClassifyEvse(string? sourceStatus)
        => sourceStatus?.Trim().ToUpperInvariant() switch
        {
            "AVAILABLE" => SnapshotEvseCategory.Available,
            "CHARGING" => SnapshotEvseCategory.Charging,
            "BLOCKED" => SnapshotEvseCategory.Blocked,
            "RESERVED" => SnapshotEvseCategory.Reserved,
            "INOPERATIVE" => SnapshotEvseCategory.Inoperative,
            "OUTOFORDER" => SnapshotEvseCategory.OutOfOrder,
            "PLANNED" => SnapshotEvseCategory.Planned,
            "REMOVED" => SnapshotEvseCategory.Removed,
            _ => SnapshotEvseCategory.Unknown
        };

    public static SnapshotLocationCategory ClassifyLocation(IEnumerable<string?> statuses)
    {
        var categories = statuses.Select(ClassifyEvse).ToArray();
        if (categories.Length == 0)
            return SnapshotLocationCategory.NoActiveCapacity;

        if (categories.Contains(SnapshotEvseCategory.Available))
            return SnapshotLocationCategory.AnyAvailable;

        var active = categories.Where(IsActiveCapacity).ToArray();
        if (active.Length == 0)
            return SnapshotLocationCategory.NoActiveCapacity;

        if (active.Contains(SnapshotEvseCategory.Unknown))
            return SnapshotLocationCategory.Uncertain;

        if (active.All(IsDefect))
            return SnapshotLocationCategory.DefectOnly;

        return SnapshotLocationCategory.NoFreeKnown;
    }

    public static double UpdateEwma(double? previous, double sample)
    {
        if (double.IsNaN(sample) || double.IsInfinity(sample) || sample < 0.0 || sample > 1.0)
            throw new ArgumentOutOfRangeException(nameof(sample), "Snapshot sample must be within [0,1].");

        return previous is null
            ? sample
            : WeeklySlotAlpha * sample + (1.0 - WeeklySlotAlpha) * previous.Value;
    }

    private static bool IsActiveCapacity(SnapshotEvseCategory category)
        => category is not SnapshotEvseCategory.Planned and not SnapshotEvseCategory.Removed;

    private static bool IsDefect(SnapshotEvseCategory category)
        => category is SnapshotEvseCategory.Inoperative or SnapshotEvseCategory.OutOfOrder;

    public static int SelfTest()
    {
        static void Require(bool condition, string message)
        {
            if (!condition)
                throw new InvalidOperationException("Compact snapshot statistics selftest failed: " + message);
        }

        Require(ClassifyEvse("AVAILABLE") == SnapshotEvseCategory.Available, "AVAILABLE classification");
        Require(ClassifyEvse("charging") == SnapshotEvseCategory.Charging, "CHARGING classification");
        Require(ClassifyEvse("BLOCKED") == SnapshotEvseCategory.Blocked, "BLOCKED classification");
        Require(ClassifyEvse("RESERVED") == SnapshotEvseCategory.Reserved, "RESERVED classification");
        Require(ClassifyEvse("INOPERATIVE") == SnapshotEvseCategory.Inoperative, "INOPERATIVE classification");
        Require(ClassifyEvse("OUTOFORDER") == SnapshotEvseCategory.OutOfOrder, "OUTOFORDER classification");
        Require(ClassifyEvse("PLANNED") == SnapshotEvseCategory.Planned, "PLANNED classification");
        Require(ClassifyEvse("REMOVED") == SnapshotEvseCategory.Removed, "REMOVED classification");
        Require(ClassifyEvse("nonsense") == SnapshotEvseCategory.Unknown, "unknown classification");

        Require(
            ClassifyLocation(["UNKNOWN", "AVAILABLE"]) == SnapshotLocationCategory.AnyAvailable,
            "AVAILABLE must dominate UNKNOWN at location level");
        Require(
            ClassifyLocation(["CHARGING", "UNKNOWN"]) == SnapshotLocationCategory.Uncertain,
            "zero free plus UNKNOWN must remain uncertain");
        Require(
            ClassifyLocation(["INOPERATIVE", "OUTOFORDER"]) == SnapshotLocationCategory.DefectOnly,
            "all-defect location classification");
        Require(
            ClassifyLocation(["PLANNED", "REMOVED"]) == SnapshotLocationCategory.NoActiveCapacity,
            "planned/removed-only location classification");
        Require(
            ClassifyLocation(["CHARGING", "BLOCKED"]) == SnapshotLocationCategory.NoFreeKnown,
            "known active non-free location classification");

        Require(Math.Abs(WeeklySlotAlpha - 0.05248399218516142) < 1e-12, "90-day weekly-slot alpha");
        Require(UpdateEwma(null, 1.0) == 1.0, "first sample initializes EWMA");
        var afterZero = UpdateEwma(1.0, 0.0);
        Require(Math.Abs(afterZero - (1.0 - WeeklySlotAlpha)) < 1e-12, "EWMA update");

        var retainedAfterNinetyDays = Math.Pow(1.0 - WeeklySlotAlpha, HalfLifeDays / 7.0);
        Require(Math.Abs(retainedAfterNinetyDays - 0.5) < 1e-12, "90-day half-life calibration");

        SnapshotHourReducerTests.SelfTest();
        Console.WriteLine(
            $"Compact snapshot statistics selftest passed. Weekly-slot alpha={WeeklySlotAlpha:F9}, half-life={HalfLifeDays:F0} days.");
        return 0;
    }
}
