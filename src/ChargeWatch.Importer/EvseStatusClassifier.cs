// Pure status semantics: no database writes and no assumption that "not available" means charging.
internal enum EvseObservationClass
{
    Available,
    Occupied,
    Fault,
    Unknown
}

internal static class EvseStatusClassifier
{
    public static EvseObservationClass Classify(string? sourceStatus) =>
        sourceStatus?.Trim().ToUpperInvariant() switch
        {
            "AVAILABLE" => EvseObservationClass.Available,
            "CHARGING" or "RESERVED" or "BLOCKED" => EvseObservationClass.Occupied,
            "INOPERATIVE" or "OUTOFORDER" => EvseObservationClass.Fault,
            _ => EvseObservationClass.Unknown
        };

    // Unknown cannot be treated as not-free: otherwise false zero-availability periods result.
    public static bool TryGetLocationAvailability(
        IEnumerable<string?> statuses,
        out bool anyAvailable)
    {
        anyAvailable = false;
        var observed = false;
        var uncertain = false;
        foreach (var status in statuses)
        {
            observed = true;
            switch (Classify(status))
            {
                case EvseObservationClass.Available:
                    anyAvailable = true;
                    break;
                case EvseObservationClass.Unknown:
                    uncertain = true;
                    break;
            }
        }
        return observed && (anyAvailable || !uncertain);
    }

    public static bool IsOperating(string? status)
        => Classify(status) is EvseObservationClass.Available or EvseObservationClass.Occupied;
}
