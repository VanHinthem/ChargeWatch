using System.Text.Json;

internal static class OcpiTariffEvaluatorTests
{
    public static void Run()
    {
        static EvaluatedTariff Check(string json, string instant = "2026-10-28T10:30:00Z",
            string? zone = "Europe/Amsterdam", OcpiSessionContext? session = null, string? tariffType = null)
        {
            using var doc = JsonDocument.Parse(json);
            return OcpiTariffEvaluator.Evaluate(doc.RootElement, DateTimeOffset.Parse(instant), zone, session, tariffType);
        }
        // Real NDW export contains explicit null for omitted optional top-level and element fields.
        const string ndwNulls = """
            {"type":null,"start_date_time":null,"end_date_time":null,
             "tariff_alt_url":null,"tariff_alt_text":null,"min_price":null,"max_price":null,
             "currency":"EUR","elements":[
               {"restrictions":{"start_time":"12:00","end_time":"13:00","day_of_week":null,
                 "min_duration":null,"max_duration":null},
                "price_components":[{"type":"ENERGY","price":0.6471,"step_size":1,"vat":null}]},
               {"restrictions":null,
                "price_components":[{"type":"ENERGY","price":0.6,"step_size":1}]}]}
            """;
        var ndwAtHour = Check(ndwNulls, "2026-09-03T10:30:00Z");
        Assert(ndwAtHour.TypeMatch == TariffMatch.Match &&
               ndwAtHour.Components.Single(x => x.Dimension == "ENERGY").Price == .6471m &&
               ndwAtHour.Components.Single().Certainty == TariffCertainty.Applicable &&
               !ndwAtHour.Limitations.Contains("INVALID_VALIDITY"),
            "real NDW explicit null optional fields permit matching hourly tariff");
        using (var ndwDocument = JsonDocument.Parse(ndwNulls))
        {
            var ndwMetadata = OcpiTariffMetadataParser.Parse(ndwDocument.RootElement);
            Assert(ndwMetadata.Limitations.Count == 0 && ndwMetadata.Currency == "EUR",
                "real NDW null optional metadata does not produce invalid warnings");
        }
        // Exact OCPI duration threshold: maximum exclusive, minimum inclusive.
        const string parkingThreshold = """
            {"elements":[
              {"price_components":[{"type":"PARKING_TIME","price":0,"step_size":1}],
               "restrictions":{"max_duration":3600,"start_date":null}},
              {"price_components":[{"type":"PARKING_TIME","price":6,"step_size":1}],
               "restrictions":{"min_duration":3600,"end_date":null}}]}
            """;
        foreach (var seconds in new long[] { 0, 3599, 3600, 3601 })
        {
            var result = Check(parkingThreshold,
                session: new OcpiSessionContext(DurationSeconds: seconds));
            Assert(result.Components.Count(x => x.Dimension == "PARKING_TIME") == 1 &&
                   result.Components.Single(x => x.Dimension == "PARKING_TIME").Price ==
                       (seconds < 3600 ? 0m : 6m) &&
                   result.Components.Single(x => x.Dimension == "PARKING_TIME").Certainty ==
                       TariffCertainty.Applicable &&
                   !result.Limitations.Contains("SESSION_CONTEXT_REQUIRED"),
                "NDW parking threshold at " + seconds + " seconds");
        }
        const string efl = """
        {"elements":[
         {"price_components":[{"type":"ENERGY","price":0.31,"step_size":1}],"restrictions":{"start_time":"11:00","end_time":"12:00"}},
         {"price_components":[{"type":"ENERGY","price":0.53,"step_size":1},{"type":"FLAT","price":0,"step_size":0},{"type":"TIME","price":0,"step_size":1}]}
        ]}
        """;
        var selected = Check(efl);
        Assert(selected.Components.Single(x => x.Dimension == "ENERGY").Price == .31m, "EFL hourly price");
        Assert(selected.Components.Count(x => x.Dimension == "ENERGY") == 1, "EFL fallback not additive");
        Assert(selected.Components.Any(x => x.Dimension == "FLAT"), "free FLAT source compatibility");
        Assert(Check(efl, "2026-10-28T13:30:00Z").Components
            .Single(x => x.Dimension == "ENERGY").Price == .53m, "EFL fallback");

        var unknown = Check("""
            {"elements":[
            {"price_components":[{"type":"ENERGY","price":0.2,"step_size":1}],"restrictions":{"min_kwh":10}},
            {"price_components":[{"type":"ENERGY","price":0.5,"step_size":1}]}]}
            """);
        Assert(unknown.Components.All(x => x.Certainty == TariffCertainty.Conditional),
            "unknown earlier session restriction blocks certainty");
        Assert(unknown.Limitations.Contains("SESSION_CONTEXT_REQUIRED"), "explicit limitation");

        var validOther = Check("""
            {"elements":[{"price_components":[
             {"type":"ENERGY","price":0.3},
             {"type":"TIME","price":0.1,"step_size":60}]}]}
            """);
        Assert(validOther.Components.Any(x => x.Dimension == "TIME"), "tolerant independent dimension");
        Assert(validOther.Components.All(x => x.Dimension != "ENERGY"), "invalid ENERGY step_size");
        Assert(validOther.Limitations.Contains("INVALID_PRICE_COMPONENT"), "missing step_size flagged");

        var overnight = Check("""
            {"elements":[
              {"price_components":[{"type":"ENERGY","price":0.2,"step_size":1}],"restrictions":{"start_time":"22:00","end_time":"06:00"}},
              {"price_components":[{"type":"ENERGY","price":0.6,"step_size":1}]}]}
            """, "2026-10-28T22:00:00Z");
        Assert(overnight.Components.Single(x=>x.Dimension=="ENERGY").Price == .2m, "overnight local");

        var noZone = Check(efl, zone:null);
        Assert(noZone.Components.All(x=>x.Dimension!="ENERGY" ||
            x.Certainty==TariffCertainty.Conditional), "no assumed timezone");


        // Complete OCPI weekday names, not numeric day indices.
        var weekday = Check("""
            {"elements":[
              {"price_components":[{"type":"ENERGY","price":0.11,"step_size":1}],"restrictions":{"day_of_week":["WEDNESDAY"]}},
              {"price_components":[{"type":"ENERGY","price":0.55,"step_size":1}]}]}
            """);
        Assert(weekday.Components.Single(x => x.Dimension == "ENERGY").Price == .11m,
            "OCPI named day_of_week");
        var invalidDay = Check("""
            {"elements":[
              {"price_components":[{"type":"ENERGY","price":0.11,"step_size":1}],"restrictions":{"day_of_week":[3]}},
              {"price_components":[{"type":"ENERGY","price":0.55,"step_size":1}]}]}
            """);
        Assert(invalidDay.Components.All(x => x.Certainty == TariffCertainty.Conditional),
            "invalid weekday blocks certain fallback");

        var withSession = Check("""
            {"elements":[
             {"price_components":[{"type":"ENERGY","price":0.15,"step_size":1}],
              "restrictions":{"min_kwh":10,"max_kwh":20,"min_current":6,"max_current":32,
                              "min_power":2,"max_power":11,"min_duration":120,"max_duration":3600}},
             {"price_components":[{"type":"ENERGY","price":0.60,"step_size":1}]}]}
            """, session:new OcpiSessionContext(EnergyKwh:10, CurrentAmps:16,
                       PowerKw:7, DurationSeconds:120));
        Assert(withSession.Components.Single(x => x.Dimension == "ENERGY").Price == .15m,
            "AND matching numeric restrictions, inclusive min");

        var outside = Check("""
            {"elements":[
             {"price_components":[{"type":"ENERGY","price":0.15,"step_size":1}],
              "restrictions":{"max_kwh":20}},
             {"price_components":[{"type":"ENERGY","price":0.60,"step_size":1}]}]}
            """, session:new OcpiSessionContext(EnergyKwh:20));
        Assert(outside.Components.Single(x => x.Dimension == "ENERGY").Price == .60m,
            "exclusive max boundary");

        var unknownPower = Check("""
            {"elements":[
             {"price_components":[{"type":"ENERGY","price":0.15,"step_size":1}],
              "restrictions":{"max_power":11}},
             {"price_components":[{"type":"ENERGY","price":0.60,"step_size":1}]}]}
            """, session:new OcpiSessionContext(EnergyKwh:12));
        Assert(unknownPower.Components.All(x => x.Certainty == TariffCertainty.Conditional),
            "partial session context cannot imply match");

        var reservation = Check("""
            {"elements":[
             {"price_components":[{"type":"FLAT","price":1,"step_size":1}],
              "restrictions":{"reservation":"RESERVATION"}},
             {"price_components":[{"type":"FLAT","price":2,"step_size":1}]}]}
            """, session:new OcpiSessionContext(Reservation:"RESERVATION"));
        Assert(reservation.Components.Single(x => x.Dimension == "FLAT").Price == 1,
            "reservation-only price");


        const string typed = """
            {"type":"PROFILE_CHEAP","elements":[{"price_components":[
              {"type":"ENERGY","price":0.20,"step_size":1}]}]}
            """;
        var noSelection = Check(typed);
        Assert(noSelection.Components.Count == 1 &&
               noSelection.Components[0].Certainty == TariffCertainty.Conditional &&
               noSelection.TypeMatch == TariffMatch.Unknown, "typed tariff without user selection");
        var cheap = Check(typed, tariffType:"PROFILE_CHEAP");
        Assert(cheap.Components.Single().Certainty == TariffCertainty.Applicable &&
               cheap.TypeMatch == TariffMatch.Match, "selected profile");
        var regular = Check(typed, tariffType:"REGULAR");
        Assert(regular.Components.Count == 0 && regular.TypeMatch == TariffMatch.NoMatch &&
               regular.Limitations.Contains("TARIFF_TYPE_MISMATCH"), "different profile excluded");
        var unspecified = Check("""
            {"elements":[{"price_components":[{"type":"ENERGY","price":0.41,"step_size":1}]}]}
            """, tariffType:"AD_HOC_PAYMENT");
        Assert(unspecified.Components.Single().Certainty == TariffCertainty.Applicable,
            "missing OCPI type applies to any requested profile");
        var malformedType = Check("""
            {"type":"NON_STANDARD","elements":[{"price_components":[
              {"type":"ENERGY","price":0.45,"step_size":1}]}]}
            """, tariffType:"REGULAR");
        Assert(malformedType.Components.Single().Certainty == TariffCertainty.Conditional &&
               malformedType.Limitations.Contains("UNSUPPORTED_TARIFF_TYPE"),
            "unknown source type not silently mapped to regular");
        var invalidRequest = Check(typed, tariffType:"PROFILE_UNKNOWN");
        Assert(invalidRequest.Components.All(x => x.Certainty == TariffCertainty.Conditional),
            "unknown requested profile not assumed");


        // Synthetic regression based on observed NDW/EFL structure (48 hour rows + fallback).
        // Exact production prices/IDs were not exported into this repository.
        var eflElements = new List<object>();
        var firstDay = new DateOnly(2026, 10, 26);
        for (var day = 0; day < 2; day++)
        {
            var date = firstDay.AddDays(day).ToString("yyyy-MM-dd");
            var nextDate = firstDay.AddDays(day + 1).ToString("yyyy-MM-dd");
            for (var hour = 0; hour < 24; hour++)
            {
                // All restrictions are AND, exact hour window and exact local date.
                eflElements.Add(new {
                    price_components = new[] { new { type = "ENERGY",
                        price = 0.20m + (day * 24 + hour) / 1000m, step_size = 1 } },
                    restrictions = new { start_date = date, end_date = nextDate,
                        start_time = hour.ToString("00") + ":00",
                        end_time = (hour + 1).ToString("00").Replace("24", "00") + ":00" }
                });
            }
        }
        eflElements.Add(new Dictionary<string, object> {
            ["price_components"] = new object[] {
                new { type = "ENERGY", price = 0.529m, step_size = 1 },
                new { type = "FLAT", price = 0m, step_size = 0 },
                new { type = "TIME", price = 0m, step_size = 1 }
            }
        });
        using (var eflDocument = JsonDocument.Parse(JsonSerializer.Serialize(
            new { elements = eflElements })))
        {
            // Validate every unique local hourly midpoint across both days.
            for (var day = 0; day < 2; day++)
            for (var hour = 0; hour < 24; hour++)
            {
                var localDate = firstDay.AddDays(day).ToDateTime(
                    new TimeOnly(hour, 30), DateTimeKind.Unspecified);
                var utc = TimeZoneInfo.ConvertTimeToUtc(localDate,
                    TimeZoneInfo.FindSystemTimeZoneById("Europe/Amsterdam"));
                var result48 = OcpiTariffEvaluator.Evaluate(eflDocument.RootElement,
                    new DateTimeOffset(utc), "Europe/Amsterdam");
                var energy = result48.Components.Single(x => x.Dimension == "ENERGY");
                Assert(energy.ElementIndex == day * 24 + hour &&
                    energy.Certainty == TariffCertainty.Applicable,
                    "48-slot EFL hourly first-match order");
                Assert(result48.Components.Count(x => x.Dimension == "ENERGY") == 1,
                    "EFL fallback never added as second energy component");
            }
            var outside48 = OcpiTariffEvaluator.Evaluate(eflDocument.RootElement,
                DateTimeOffset.Parse("2026-10-28T12:30:00Z"), "Europe/Amsterdam");
            Assert(outside48.Components.Single(x => x.Dimension == "ENERGY").Price == .529m,
                "EFL fallback beyond 48 slots");
        }

        using (var metadataDocument = JsonDocument.Parse("""
            {"currency":"EUR","min_price":{"excl_vat":1.5,"incl_vat":1.815},
             "max_price":{"excl_vat":50},"tariff_alt_url":"https://example.org/tariff",
             "tariff_alt_text":[{"language":"en","text":"Example CPO tariff"}]}
            """))
        {
            var meta = OcpiTariffMetadataParser.Parse(metadataDocument.RootElement);
            Assert(meta.Currency == "EUR" && meta.MinimumPrice == 1.5m &&
                   meta.MaximumPrice == 50m && meta.HasAlternativeText &&
                   meta.AlternativeUrl is not null && meta.Limitations.Count == 0,
                   "OCPI tariff metadata");
        }
        using (var malformedDocument = JsonDocument.Parse("""
            {"currency":"eur","min_price":{"excl_vat":60},
             "max_price":{"excl_vat":20},"tariff_alt_url":"javascript:alert(1)"}
            """))
        {
            var meta = OcpiTariffMetadataParser.Parse(malformedDocument.RootElement);
            Assert(meta.Currency == null && meta.MinimumPrice == 60m &&
                   meta.Limitations.Contains("INVALID_CURRENCY") &&
                   meta.Limitations.Contains("INVALID_ALTERNATIVE_URL") &&
                   meta.Limitations.Contains("INCONSISTENT_PRICE_BOUNDS"),
                   "metadata partial tolerance");
        }


        // Reduced, hand-transcribed fixture from actual NDW/EFL tariff DB row 29326.
        // Important: source contains all optional restriction keys explicitly as null.
        const string realEflSample = """
            {"currency":"EUR","elements":[
              {"price_components":[{"type":"ENERGY","price":0.6471,"step_size":1}],
               "restrictions":{"start_date":"2026-09-03","end_date":"2026-09-04",
                 "start_time":"00:00","end_time":"01:00","day_of_week":null,
                 "min_kwh":null,"max_kwh":null,"max_power":null,"min_duration":null}},
              {"price_components":[{"type":"ENERGY","price":0.6408,"step_size":1}],
               "restrictions":{"start_date":"2026-09-03","end_date":"2026-09-04",
                 "start_time":"01:00","end_time":"02:00","reservation":null}},
              {"price_components":[{"type":"ENERGY","price":0.6107,"step_size":1}],
               "restrictions":{"start_date":"2026-09-04","end_date":"2026-09-05",
                 "start_time":"23:00","end_time":"00:00","min_duration":null}},
              {"price_components":[{"type":"FLAT","price":0,"step_size":1},
                {"type":"TIME","price":0,"step_size":1},
                {"type":"ENERGY","price":0.6,"step_size":1}]},
              {"price_components":[{"type":"PARKING_TIME","price":0,"step_size":1}],
               "restrictions":{"max_duration":3600,"start_date":null}},
              {"price_components":[{"type":"PARKING_TIME","price":6,"step_size":1}],
               "restrictions":{"min_duration":3600,"start_time":null}}
            ]}
            """;
        var realAtFirstHour = Check(realEflSample, "2026-09-02T22:30:00Z",
            session:new OcpiSessionContext(DurationSeconds:3599));
        Assert(realAtFirstHour.Components.Single(x => x.Dimension == "ENERGY").Price == .6471m &&
               realAtFirstHour.Components.Single(x => x.Dimension == "ENERGY").Certainty == TariffCertainty.Applicable,
            "authentic EFL null restrictions do not block active hourly energy");
        Assert(realAtFirstHour.Components.Single(x => x.Dimension == "PARKING_TIME").Price == 0m,
            "authentic EFL parking before duration threshold");
        var realAtThreshold = Check(realEflSample, "2026-09-02T22:30:00Z",
            session:new OcpiSessionContext(DurationSeconds:3600));
        Assert(realAtThreshold.Components.Single(x => x.Dimension == "PARKING_TIME").Price == 6m,
            "authentic EFL parking at duration threshold");
        var realFallback = Check(realEflSample, "2026-09-05T10:30:00Z");
        Assert(realFallback.Components.Single(x => x.Dimension == "ENERGY").Price == 0.6m &&
               realFallback.Components.Count(x => x.Dimension == "ENERGY") == 1,
            "authentic EFL unrestricted fallback after hourly range");

        var expired = Check("""
            {"end_date_time":"2026-10-28T10:30:00Z",
             "elements":[{"price_components":[{"type":"ENERGY","price":1,"step_size":1}]}]}
            """);
        Assert(expired.Components.Count==0 && expired.Limitations.Contains("OUTSIDE_VALIDITY"),
            "exclusive end validity");
    }

    private static void Assert(bool condition, string name)
    {
        if (!condition) throw new InvalidOperationException("Tariff evaluator selftest failed: " + name);
    }
}
