using System.Globalization;
using System.Text.Json;

// Offline, read-only diagnostic for a single saved real NDW tariff payload.
// Does not connect to the database, print full payloads, or persist computed prices.
internal static class OcpiRealTariffValidation
{
    public static void Run(string[] args)
    {
        if (args.Length < 4 || args.Length > 6)
            throw new ArgumentException("Usage: --validate-tariff-file PATH IANA_TIME_ZONE AS_OF_UTC [REQUESTED_TARIFF_TYPE] [--duration-seconds=N]");

        string? requestedType = null;
        long? durationSeconds = null;
        foreach (var argument in args.Skip(4))
        {
            const string durationPrefix = "--duration-seconds=";
            if (argument.StartsWith(durationPrefix, StringComparison.Ordinal))
            {
                if (durationSeconds.HasValue || !long.TryParse(argument[durationPrefix.Length..], NumberStyles.None,
                        CultureInfo.InvariantCulture, out var parsed) || parsed < 0)
                    throw new ArgumentException("Duration must be one non-negative integer number of seconds.");
                durationSeconds = parsed;
            }
            else if (requestedType is null && !argument.StartsWith("--", StringComparison.Ordinal))
                requestedType = argument;
            else
                throw new ArgumentException("Unknown or duplicate tariff validation argument: " + argument);
        }

        var path = args[1];
        var zone = args[2];
        if (!DateTimeOffset.TryParse(args[3], CultureInfo.InvariantCulture,
                DateTimeStyles.None, out var instant) || instant.Offset != TimeSpan.Zero ||
            !args[3].EndsWith("Z", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("AS_OF_UTC must be an explicit UTC ISO-8601 instant ending in Z.");

        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        var root = doc.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
            throw new ArgumentException("Expected one OCPI tariff object.");

        var metadata = OcpiTariffMetadataParser.Parse(root);
        var evaluation = OcpiTariffEvaluator.Evaluate(root, instant, zone,
            session: durationSeconds.HasValue ? new OcpiSessionContext(DurationSeconds: durationSeconds.Value) : null,
            requestedTariffType: requestedType);

        var count = root.TryGetProperty("elements", out var els) && els.ValueKind == JsonValueKind.Array
            ? els.GetArrayLength() : 0;
        var dimensions = evaluation.Components
            .Select(x => new { type = x.Dimension, price = x.Price,
                x.StepSize, x.ElementIndex, certainty = x.Certainty.ToString(),
                vatPercent = x.VatPercent })
            .ToArray();

        // Deliberately print no source IDs, contact details, alternative text or raw JSON.
        Console.WriteLine(JsonSerializer.Serialize(new
        {
            source = "LOCAL_OCPI_TARIFF_FILE",
            asOfUtc = instant.ToUniversalTime().ToString("O"),
            timeZone = zone,
            sessionDurationSeconds = durationSeconds,
            elementCount = count,
            sourceTariffType = evaluation.SourceTariffType,
            tariffTypeMatch = evaluation.TypeMatch.ToString(),
            currency = metadata.Currency,
            minPriceExclVat = metadata.MinimumPrice,
            maxPriceExclVat = metadata.MaximumPrice,
            components = dimensions,
            limitations = evaluation.Limitations.Concat(metadata.Limitations).Distinct().OrderBy(x => x).ToArray(),
            note = "Diagnostic CPO component prices, NOT final charging or eMSP prices."
        }, new JsonSerializerOptions { WriteIndented = true }));
    }
}
