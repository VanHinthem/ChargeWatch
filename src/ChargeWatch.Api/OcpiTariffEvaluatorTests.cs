using System.Text.Json;

internal static class OcpiTariffEvaluatorTests
{
    public static void Run()
    {
        static EvaluatedTariff Check(string json, string instant = "2026-10-28T10:30:00Z",
            string? zone = "Europe/Amsterdam", OcpiSessionContext? session = null)
        {
            using var doc = JsonDocument.Parse(json);
            return OcpiTariffEvaluator.Evaluate(doc.RootElement, DateTimeOffset.Parse(instant), zone, session);
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
