using System.Globalization;
using System.Text.Json;

// Pure, side-effect-free OCPI tariff evaluation. No DB/API contract changes in this phase.
internal enum TariffMatch { Match, NoMatch, Unknown }
internal enum TariffCertainty { Applicable, Conditional, Unavailable }
internal sealed record EvaluatedTariffComponent(
    string Dimension, decimal Price, int? StepSize, decimal? VatPercent,
    int ElementIndex, TariffCertainty Certainty);
internal sealed record EvaluatedTariff(
    IReadOnlyList<EvaluatedTariffComponent> Components, IReadOnlyList<string> Limitations);

internal static class OcpiTariffEvaluator
{
    private static readonly string[] Dimensions = ["ENERGY", "TIME", "PARKING_TIME", "FLAT"];
    private static readonly HashSet<string> SupportedRestrictions = new(StringComparer.Ordinal)
    {
        "start_time", "end_time", "start_date", "end_date", "day_of_week",
        "min_kwh", "max_kwh", "min_current", "max_current", "min_power",
        "max_power", "min_duration", "max_duration", "reservation"
    };

    // Context-dependent restrictions are intentionally UNKNOWN until actual session context exists.
    public static EvaluatedTariff Evaluate(JsonElement tariff, DateTimeOffset asOfUtc, string? locationTimeZone, OcpiSessionContext? session = null)
    {
        var limitations = new HashSet<string>(StringComparer.Ordinal);
        var result = new List<EvaluatedTariffComponent>();
        if (tariff.ValueKind != JsonValueKind.Object ||
            !tariff.TryGetProperty("elements", out var elements) ||
            elements.ValueKind != JsonValueKind.Array)
            return new(result, ["INVALID_ELEMENTS"]);

        if (!WithinTopLevelValidity(tariff, asOfUtc, limitations))
            return new(result, limitations.OrderBy(x => x).ToArray());

        TimeZoneInfo? zone = null;
        if (!string.IsNullOrWhiteSpace(locationTimeZone))
        {
            try { zone = TimeZoneInfo.FindSystemTimeZoneById(locationTimeZone); }
            catch (TimeZoneNotFoundException) { limitations.Add("INVALID_TIME_ZONE"); }
            catch (InvalidTimeZoneException) { limitations.Add("INVALID_TIME_ZONE"); }
        }
        var local = zone is null ? (DateTimeOffset?)null : TimeZoneInfo.ConvertTime(asOfUtc, zone);
        var ordered = elements.EnumerateArray().ToArray();

        foreach (var dimension in Dimensions)
        {
            var previousUnknown = false;
            for (var i = 0; i < ordered.Length; i++)
            {
                var element = ordered[i];
                if (element.ValueKind != JsonValueKind.Object ||
                    !element.TryGetProperty("price_components", out var components) ||
                    components.ValueKind != JsonValueKind.Array)
                {
                    limitations.Add("INVALID_ELEMENT");
                    previousUnknown = true; // An unparseable earlier element might contain this dimension.
                    continue;
                }
                var matches = components.EnumerateArray().Where(c =>
                    c.ValueKind == JsonValueKind.Object &&
                    c.TryGetProperty("type", out var t) &&
                    t.ValueKind == JsonValueKind.String && t.GetString() == dimension).ToArray();
                if (matches.Length == 0) continue;

                var match = MatchRestrictions(element, local, session, limitations);
                if (match == TariffMatch.NoMatch) continue;
                foreach (var component in matches)
                {
                    if (!component.TryGetProperty("price", out var priceJson) ||
                        priceJson.ValueKind != JsonValueKind.Number ||
                        !priceJson.TryGetDecimal(out var price) || price < 0 ||
                        !component.TryGetProperty("step_size", out var stepJson) ||
                        stepJson.ValueKind != JsonValueKind.Number ||
                        !stepJson.TryGetInt32(out var step) ||
                        (step <= 0 && !(dimension == "FLAT" && step == 0 && price == 0)))
                    {
                        limitations.Add("INVALID_PRICE_COMPONENT");
                        previousUnknown = true;
                        continue;
                    }
                    decimal? vat = null;
                    if (component.TryGetProperty("vat", out var vatJson))
                    {
                        if (vatJson.ValueKind != JsonValueKind.Number ||
                            !vatJson.TryGetDecimal(out var rate) || rate < 0 || rate > 100)
                        {
                            limitations.Add("INVALID_VAT");
                            previousUnknown = true;
                            continue;
                        }
                        vat = rate;
                    }
                    var certainty = match == TariffMatch.Match && !previousUnknown
                        ? TariffCertainty.Applicable : TariffCertainty.Conditional;
                    result.Add(new(dimension, price, step, vat, i, certainty));
                    if (certainty == TariffCertainty.Conditional) limitations.Add("CONDITIONAL");
                }
                // A valid candidate is definitive if no earlier element could match.
                // UNKNOWN still blocks any later element from being presented as certain.
                if (match == TariffMatch.Match && !previousUnknown &&
                    result.Any(c => c.Dimension == dimension && c.ElementIndex == i))
                    break;
                previousUnknown = true;
            }
        }
        return new(result, limitations.OrderBy(x => x).ToArray());
    }

    private static bool WithinTopLevelValidity(JsonElement tariff, DateTimeOffset instant, HashSet<string> limitations)
    {
        foreach (var (key, isStart) in new[] { ("start_date_time", true), ("end_date_time", false) })
        {
            if (!tariff.TryGetProperty(key, out var value)) continue;
            if (value.ValueKind != JsonValueKind.String ||
                !DateTimeOffset.TryParse(value.GetString(), CultureInfo.InvariantCulture,
                    DateTimeStyles.None, out var boundary))
            {
                limitations.Add("INVALID_VALIDITY"); return false;
            }
            if (isStart && instant < boundary || !isStart && instant >= boundary)
            {
                limitations.Add("OUTSIDE_VALIDITY"); return false;
            }
        }
        return true;
    }

    private static TariffMatch MatchRestrictions(JsonElement element, DateTimeOffset? local,
        OcpiSessionContext? session, HashSet<string> limitations)
    {
        if (!element.TryGetProperty("restrictions", out var restrictions)) return TariffMatch.Match;
        if (restrictions.ValueKind != JsonValueKind.Object)
        {
            limitations.Add("INVALID_RESTRICTIONS"); return TariffMatch.Unknown;
        }
        var unknown = false;
        foreach (var property in restrictions.EnumerateObject())
        {
            if (!SupportedRestrictions.Contains(property.Name))
            {
                limitations.Add("UNKNOWN_RESTRICTION"); unknown = true; continue;
            }
            if (property.Name is not ("start_time" or "end_time" or "start_date" or "end_date" or "day_of_week"))
            {
                var sessionMatch = OcpiSessionRestrictions.Evaluate(property, session, limitations);
                if (sessionMatch == TariffMatch.NoMatch) return TariffMatch.NoMatch;
                if (sessionMatch == TariffMatch.Unknown) unknown = true;
                continue;
            }
            if (local is null)
            {
                limitations.Add("TIME_ZONE_REQUIRED"); unknown = true; continue;
            }
            var match = EvaluateLocalRestriction(property, restrictions, local.Value);
            if (match == TariffMatch.NoMatch) return TariffMatch.NoMatch;
            if (match == TariffMatch.Unknown)
            {
                limitations.Add("INVALID_RESTRICTION_VALUE"); unknown = true;
            }
        }
        return unknown ? TariffMatch.Unknown : TariffMatch.Match;
    }

    private static TariffMatch EvaluateLocalRestriction(JsonProperty property, JsonElement restrictions, DateTimeOffset local)
    {
        var value = property.Value;
        if (property.Name == "day_of_week")
        {
            if (value.ValueKind != JsonValueKind.Array) return TariffMatch.Unknown;
            var days = value.EnumerateArray().ToArray();
            if (days.Any(x => x.ValueKind != JsonValueKind.String ||
                !Enum.TryParse<DayOfWeek>(x.GetString(), true, out var parsed) ||
                !string.Equals(x.GetString(), parsed.ToString().ToUpperInvariant(), StringComparison.Ordinal)))
                return TariffMatch.Unknown;
            return days.Any(x => x.GetString() == local.DayOfWeek.ToString().ToUpperInvariant())
                ? TariffMatch.Match : TariffMatch.NoMatch;
        }
        if (value.ValueKind != JsonValueKind.String) return TariffMatch.Unknown;
        if (property.Name is "start_date" or "end_date")
        {
            if (!DateOnly.TryParseExact(value.GetString(), "yyyy-MM-dd", CultureInfo.InvariantCulture,
                    DateTimeStyles.None, out var date)) return TariffMatch.Unknown;
            var today = DateOnly.FromDateTime(local.Date);
            return (property.Name == "start_date" ? today >= date : today < date)
                ? TariffMatch.Match : TariffMatch.NoMatch;
        }
        // Treat start/end times as a single interval; overnight window is intentional OCPI behavior.
        if (!TryTime(restrictions, "start_time", out var start, out var hasStart) ||
            !TryTime(restrictions, "end_time", out var end, out var hasEnd))
            return TariffMatch.Unknown;
        var now = TimeOnly.FromDateTime(local.DateTime);
        var fits = !hasStart && !hasEnd || !hasStart && now < end ||
                   !hasEnd && now >= start ||
                   hasStart && hasEnd && (start <= end
                       ? now >= start && now < end : now >= start || now < end);
        return fits ? TariffMatch.Match : TariffMatch.NoMatch;
    }

    private static bool TryTime(JsonElement parent, string field, out TimeOnly result, out bool present)
    {
        result = default;
        present = parent.TryGetProperty(field, out var value);
        return !present || value.ValueKind == JsonValueKind.String &&
            TimeOnly.TryParseExact(value.GetString(), "HH:mm", CultureInfo.InvariantCulture,
                DateTimeStyles.None, out result);
    }
}
