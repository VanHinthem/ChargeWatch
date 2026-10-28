using System.Text.Json;

// Values are in OCPI units: kWh, Ampere, kW, seconds.
// Null means no trusted measurement was supplied, never zero.
internal sealed record OcpiSessionContext(
    decimal? EnergyKwh = null,
    decimal? CurrentAmps = null,
    decimal? PowerKw = null,
    long? DurationSeconds = null,
    string? Reservation = null);

internal static class OcpiSessionRestrictions
{
    public static TariffMatch Evaluate(JsonProperty property, OcpiSessionContext? session,
        HashSet<string> limitations)
    {
        if (property.Name == "reservation")
        {
            if (property.Value.ValueKind != JsonValueKind.String ||
                property.Value.GetString() is not ("RESERVATION" or "RESERVATION_EXPIRES"))
            {
                limitations.Add("INVALID_RESTRICTION_VALUE");
                return TariffMatch.Unknown;
            }
            if (session?.Reservation is null)
            {
                limitations.Add("SESSION_CONTEXT_REQUIRED");
                return TariffMatch.Unknown;
            }
            if (session.Reservation is not ("RESERVATION" or "RESERVATION_EXPIRES" or "NONE"))
            {
                limitations.Add("INVALID_SESSION_CONTEXT");
                return TariffMatch.Unknown;
            }
            return property.Value.GetString() == session.Reservation ? TariffMatch.Match : TariffMatch.NoMatch;
        }

        if (property.Value.ValueKind != JsonValueKind.Number ||
            !property.Value.TryGetDecimal(out var boundary) || boundary < 0)
        {
            limitations.Add("INVALID_RESTRICTION_VALUE");
            return TariffMatch.Unknown;
        }

        decimal? actual = property.Name switch
        {
            "min_kwh" or "max_kwh" => session?.EnergyKwh,
            "min_current" or "max_current" => session?.CurrentAmps,
            "min_power" or "max_power" => session?.PowerKw,
            "min_duration" or "max_duration" => session?.DurationSeconds,
            _ => null
        };
        if (actual is null)
        {
            limitations.Add("SESSION_CONTEXT_REQUIRED");
            return TariffMatch.Unknown;
        }
        if (actual < 0)
        {
            limitations.Add("INVALID_SESSION_CONTEXT");
            return TariffMatch.Unknown;
        }

        // OCPI min restrictions are inclusive; max restrictions are exclusive.
        return property.Name.StartsWith("min_", StringComparison.Ordinal)
            ? actual >= boundary ? TariffMatch.Match : TariffMatch.NoMatch
            : actual < boundary ? TariffMatch.Match : TariffMatch.NoMatch;
    }
}
