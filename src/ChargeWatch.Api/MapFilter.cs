using Microsoft.AspNetCore.Http;
using Npgsql;
using NpgsqlTypes;

// Only the current read-only map and nearest-location endpoints consume these filters.
// Omitting every filter retains the previous SQL/result contract exactly.
internal sealed record MapFilter(
    string[] ConnectorTypes, string[] Modes, string? Power, int MinFree, string[] Operators)
{
    public static readonly MapFilter None = new([], [], null, 0, []);
    public bool Active => ConnectorTypes.Length > 0 || Modes.Length > 0 ||
                          Power is not null || MinFree > 0 || Operators.Length > 0;

    public static bool TryParse(IQueryCollection query, out MapFilter filter, out string error)
    {
        filter = None;
        error = "";
        static string[] Read(IQueryCollection q, string key) =>
            q.TryGetValue(key, out var value) ? value.Select(x => x?.Trim() ?? "").ToArray() : [];
        var types = Read(query, "connectorType").Select(x => x.ToLowerInvariant()).Distinct().ToArray();
        var modes = Read(query, "mode").Select(x => x.ToLowerInvariant()).Distinct().ToArray();
        var powers = Read(query, "power");
        var free = Read(query, "minFree");
        var operators = Read(query, "operator").Distinct(StringComparer.Ordinal).ToArray();
        if (types.Any(x => x is not ("type2" or "ccs2" or "chademo" or "other")) ||
            modes.Any(x => x is not ("ac" or "dc")) ||
            powers.Length > 1 || (powers.Length == 1 && powers[0] is not ("lt50" or "gte50" or "gte150" or "gte300")) ||
            free.Length > 1 || (free.Length == 1 && free[0] is not ("0" or "1" or "2")) ||
            operators.Any(string.IsNullOrWhiteSpace) ||
            types.Length > 4 || modes.Length > 2 || operators.Length > 100)
        {
            error = "Invalid connectorType, mode, power, minFree or operator filter.";
            return false;
        }
        filter = new MapFilter(types,modes,powers.FirstOrDefault(),
            free.Length == 0 ? 0 : int.Parse(free[0]), operators);
        return true;
    }

    // Same precedence and arithmetic as ConnectorPower.Estimate; source and
    // derived power values intentionally have IDENTICAL filter semantics.
    internal const string EffectiveWattsSql = """
        CASE WHEN c.max_electric_power > 0 THEN c.max_electric_power::bigint
             WHEN c.max_voltage > 0 AND c.max_amperage > 0 THEN
                 CASE WHEN c.power_type = 'AC_1_PHASE' THEN
                          round((c.max_voltage::numeric * c.max_amperage))::bigint
                      WHEN c.power_type = 'AC_3_PHASE' THEN
                          round(((CASE WHEN c.max_voltage <= 300 THEN 3.0
                                       ELSE sqrt(3.0) END) * c.max_voltage * c.max_amperage)::numeric)::bigint
                      WHEN c.power_type = 'DC' THEN
                          round((c.max_voltage::numeric * c.max_amperage))::bigint
                 END
        END
        """;

    // Build parameterized predicates against the same 'l' alias on both endpoints.
    // Both existential matches and availability counts are on UNIQUE EVSE rows.
    public string LocationPredicate(int startParameter)
    {
        if (!Active) return "";
        var next = startParameter;
        var conditions = new List<string>();
        if (Operators.Length > 0)
            conditions.Add($"btrim(l.operator_name) = ANY(${next++}::text[])");
        var connector = new List<string> { "c.evse_id=e.id", "c.is_active" };
        if (ConnectorTypes.Length > 0)
            connector.Add($"(CASE upper(coalesce(c.standard,'')) WHEN 'IEC_62196_T2' THEN 'type2' WHEN 'IEC_62196_T2_COMBO' THEN 'ccs2' WHEN 'CHADEMO' THEN 'chademo' ELSE 'other' END) = ANY(${next++}::text[])");
        if (Modes.Length > 0)
            connector.Add($"(CASE WHEN upper(coalesce(c.power_type,'')) IN ('AC_1_PHASE','AC_3_PHASE') THEN 'ac' WHEN upper(coalesce(c.power_type,''))='DC' THEN 'dc' ELSE 'other' END) = ANY(${next++}::text[])");
        var powerCondition = Power switch {
            "lt50" => $"({EffectiveWattsSql}) > 0 AND ({EffectiveWattsSql}) < 50000",
            "gte50" => $"({EffectiveWattsSql}) >= 50000",
            "gte150" => $"({EffectiveWattsSql}) >= 150000",
            "gte300" => $"({EffectiveWattsSql}) >= 300000",
            _ => null
        };
        if (powerCondition is not null) connector.Add(powerCondition);
        var operational = "e.location_id=l.id AND e.is_active AND upper(btrim(coalesce(e.status,''))) NOT IN ('REMOVED','PLANNED')";
        var technical = ConnectorTypes.Length > 0 || Modes.Length > 0 || Power is not null;
        var connectorExists = $"EXISTS (SELECT 1 FROM connector c WHERE {string.Join(" AND ",connector)})";
        if (technical)
            conditions.Add($"EXISTS (SELECT 1 FROM evse e WHERE {operational} AND {connectorExists})");
        if (MinFree > 0)
            conditions.Add($"(SELECT count(*) FROM evse e WHERE {operational} AND upper(btrim(e.status))='AVAILABLE'{(technical ? $" AND {connectorExists}" : "")}) >= {MinFree}");
        return conditions.Count > 0 ? " AND " + string.Join(" AND ",conditions.Select(x => "(" + x + ")")) : "";
    }

    public void AddParameters(NpgsqlCommand cmd)
    {
        if (Operators.Length > 0) AddArray(cmd, Operators);
        if (ConnectorTypes.Length > 0) AddArray(cmd, ConnectorTypes);
        if (Modes.Length > 0) AddArray(cmd, Modes);
    }
    private static void AddArray(NpgsqlCommand command, string[] values) =>
        command.Parameters.Add(new NpgsqlParameter {
            NpgsqlDbType = NpgsqlDbType.Array | NpgsqlDbType.Text,
            Value = values
        });

    public static void SelfTest()
    {
        var q = new QueryCollection(new Dictionary<string, Microsoft.Extensions.Primitives.StringValues> {
            ["connectorType"] = new Microsoft.Extensions.Primitives.StringValues(["ccs2","chademo"]),
            ["power"] = "gte150", ["minFree"] = "1"
        });
        if (!TryParse(q,out var f,out _) || f.ConnectorTypes.Length != 2 ||
            !f.LocationPredicate(5).Contains("150000"))
            throw new InvalidOperationException("Map filter contract self-test failed.");
        var invalid = new QueryCollection(new Dictionary<string, Microsoft.Extensions.Primitives.StringValues> {
            ["power"] = "something"
        });
        if (TryParse(invalid,out _,out _))
            throw new InvalidOperationException("Invalid power value accepted.");
    }
}
