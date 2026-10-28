using System.Text.Json;

// Metadata is descriptive: price bounds are not applied to individual price components.
// The source tariff JSON remains authoritative and is never rewritten by the evaluator.
internal sealed record OcpiTariffMetadata(
    string? Currency, decimal? MinimumPrice, decimal? MaximumPrice,
    string? AlternativeUrl, bool HasAlternativeText, IReadOnlyList<string> Limitations);

internal static class OcpiTariffMetadataParser
{
    public static OcpiTariffMetadata Parse(JsonElement tariff)
    {
        var warnings = new HashSet<string>(StringComparer.Ordinal);
        if (tariff.ValueKind != JsonValueKind.Object)
            return new(null, null, null, null, false, ["INVALID_TARIFF"]);

        string? currency = null;
        if (tariff.TryGetProperty("currency", out var currencyValue))
        {
            if (currencyValue.ValueKind == JsonValueKind.String &&
                currencyValue.GetString() is { Length: 3 } code &&
                code.All(c => c is >= 'A' and <= 'Z'))
                currency = code;
            else warnings.Add("INVALID_CURRENCY");
        }

        decimal? min = ParsePrice(tariff, "min_price", warnings);
        decimal? max = ParsePrice(tariff, "max_price", warnings);
        if (min.HasValue && max.HasValue && min > max)
            warnings.Add("INCONSISTENT_PRICE_BOUNDS");

        string? url = null;
        if (tariff.TryGetProperty("tariff_alt_url", out var urlValue))
        {
            if (urlValue.ValueKind == JsonValueKind.String &&
                Uri.TryCreate(urlValue.GetString(), UriKind.Absolute, out var candidate) &&
                candidate.Scheme is "http" or "https")
                url = candidate.ToString();
            else warnings.Add("INVALID_ALTERNATIVE_URL");
        }

        var hasText = false;
        if (tariff.TryGetProperty("tariff_alt_text", out var altText))
        {
            if (altText.ValueKind == JsonValueKind.Array)
            {
                hasText = altText.GetArrayLength() > 0;
                if (altText.EnumerateArray().Any(x => x.ValueKind != JsonValueKind.Object ||
                    !x.TryGetProperty("language", out var language) ||
                    language.ValueKind != JsonValueKind.String ||
                    !x.TryGetProperty("text", out var text) ||
                    text.ValueKind != JsonValueKind.String))
                    warnings.Add("INVALID_ALTERNATIVE_TEXT");
            }
            else warnings.Add("INVALID_ALTERNATIVE_TEXT");
        }

        return new(currency, min, max, url, hasText, warnings.OrderBy(x=>x).ToArray());
    }

    private static decimal? ParsePrice(JsonElement tariff, string name, HashSet<string> warnings)
    {
        if (!tariff.TryGetProperty(name, out var value)) return null;
        if (value.ValueKind == JsonValueKind.Object &&
            value.TryGetProperty("excl_vat", out var amount) &&
            amount.ValueKind == JsonValueKind.Number &&
            amount.TryGetDecimal(out var number) && number >= 0)
            return number;
        warnings.Add("INVALID_" + name.ToUpperInvariant());
        return null;
    }
}
