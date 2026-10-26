using System.Text.Json;

// A display-safe interpretation of OCPI tariff elements. Every element is kept
// separate; no component is ever advertised as a complete charging-session price.
internal sealed record TariffPriceComponent(string Type, decimal Price, int? StepSize);
internal sealed record TariffElementPresentation(
    IReadOnlyList<TariffPriceComponent> Components,
    JsonElement? Restrictions);
internal sealed record TariffPresentation(
    string? Currency,
    IReadOnlyList<TariffElementPresentation> Elements,
    bool HasUnsupportedData);

internal static class OcpiTariffPresentation
{
    private static readonly HashSet<string> KnownTypes = new(StringComparer.Ordinal)
        { "ENERGY", "TIME", "PARKING_TIME", "FLAT" };

    public static TariffPresentation Parse(JsonElement details, string? currency)
    {
        var elements = new List<TariffElementPresentation>();
        bool unsupported = false;
        if (details.ValueKind != JsonValueKind.Object ||
            !details.TryGetProperty("elements", out var source) ||
            source.ValueKind != JsonValueKind.Array)
            return new(currency, elements, true);

        foreach (var element in source.EnumerateArray())
        {
            if (element.ValueKind != JsonValueKind.Object ||
                !element.TryGetProperty("price_components", out var prices) ||
                prices.ValueKind != JsonValueKind.Array)
            {
                unsupported = true;
                continue;
            }
            var components = new List<TariffPriceComponent>();
            foreach (var component in prices.EnumerateArray())
            {
                if (component.ValueKind != JsonValueKind.Object ||
                    !component.TryGetProperty("type", out var typeJson) ||
                    typeJson.ValueKind != JsonValueKind.String ||
                    !component.TryGetProperty("price", out var priceJson) ||
                    priceJson.ValueKind != JsonValueKind.Number ||
                    !priceJson.TryGetDecimal(out var price) ||
                    price < 0)
                {
                    unsupported = true;
                    continue;
                }
                var type = typeJson.GetString()!;
                if (!KnownTypes.Contains(type))
                {
                    unsupported = true;
                    continue;
                }
                int? stepSize = null;
                if (component.TryGetProperty("step_size", out var step))
                {
                    if (step.ValueKind != JsonValueKind.Number ||
                        !step.TryGetInt32(out var parsedStep) || parsedStep <= 0)
                    {
                        unsupported = true;
                        continue;
                    }
                    stepSize = parsedStep;
                }
                components.Add(new(type, price, stepSize));
            }
            JsonElement? restrictions = null;
            if (element.TryGetProperty("restrictions", out var rawRestrictions))
            {
                if (rawRestrictions.ValueKind != JsonValueKind.Object)
                    unsupported = true;
                else restrictions = rawRestrictions.Clone();
            }
            if (components.Count > 0) elements.Add(new(components, restrictions));
            else unsupported = true;
        }
        // Unrecognized top-level fields are preserved in Details by the API, never silently
        // treated as understood pricing rules.
        return new(currency, elements, unsupported || elements.Count == 0);
    }

    public static void SelfTest()
    {
        using var example = JsonDocument.Parse("""
            {"elements":[
              {"price_components":[{"type":"ENERGY","price":0.49,"step_size":1}],
               "restrictions":{"start_time":"08:00","end_time":"20:00"}},
              {"price_components":[{"type":"PARKING_TIME","price":0.10}]}
            ]}
            """);
        var parsed = Parse(example.RootElement, "EUR");
        if (parsed.HasUnsupportedData || parsed.Elements.Count != 2 ||
            parsed.Elements[0].Components[0].Price != 0.49m ||
            parsed.Elements[0].Restrictions is null ||
            parsed.Elements[1].Components[0].Type != "PARKING_TIME")
            throw new InvalidOperationException("Tariff presentation selftest failed: separate priced elements and restrictions.");
        using var unknown = JsonDocument.Parse("""{"elements":[{"price_components":[{"type":"OTHER","price":1}]}]}""");
        if (!Parse(unknown.RootElement, "EUR").HasUnsupportedData)
            throw new InvalidOperationException("Tariff presentation selftest failed: unsupported tariff must not appear complete.");
        using var malformed = JsonDocument.Parse("""{"elements":[{"price_components":[{"type":"ENERGY","price":-1}]}]}""");
        if (Parse(malformed.RootElement, "EUR").Elements.Count != 0)
            throw new InvalidOperationException("Tariff presentation selftest failed: negative prices.");
    }
}
