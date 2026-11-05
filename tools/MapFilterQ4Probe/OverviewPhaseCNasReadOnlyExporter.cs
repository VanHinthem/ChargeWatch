using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using Npgsql;
using NpgsqlTypes;

// Produces one operator-reviewed SQL-only batch from the real phase-C SQL,
// MapFilter and Web Mercator grid selector. No connector to the NAS/database.
internal static class OverviewPhaseCNasReadOnlyExporter
{
    private sealed record Scenario(
        string Name, Bbox Bbox, double Zoom,
        Dictionary<string, StringValues> FilterQuery);

    public static string Create()
    {
        var scenarios = new[]
        {
            new Scenario("NATIONAL_UNFILTERED_Z6",
                new Bbox(3.00, 50.60, 7.40, 53.80), 6, new()),
            new Scenario("NATIONAL_CCS2_DC_Z12",
                new Bbox(3.00, 50.60, 7.40, 53.80), 12,
                new() { ["connectorType"] = "ccs2", ["mode"] = "dc" }),
            new Scenario("OSS_UNFILTERED_Z15",
                new Bbox(5.40, 51.70, 5.62, 51.83), 15, new())
        };
        var output = new StringBuilder();
        output.AppendLine("-- ChargeWatch #113 phase C; production-generated canonical SQL; one read-only snapshot");
        output.AppendLine("-- Standalone psql batch. No image, API, flag, schema or container changes.");
        output.AppendLine(@"\set ON_ERROR_STOP on");
        output.AppendLine(@"\pset pager off");
        output.AppendLine(@"\timing on");
        output.AppendLine("SET ROLE chargewatch_read;");
        output.AppendLine("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;");
        output.AppendLine("SET LOCAL statement_timeout = '5s';");
        output.AppendLine("SET LOCAL lock_timeout = '1s';");
        output.AppendLine("SET LOCAL jit = off;");
        foreach (var scenario in scenarios)
            EmitScenario(output, scenario);
        output.AppendLine("ROLLBACK;");
        output.AppendLine("RESET ROLE;");
        var script = output.ToString();
        if (Regex.Matches(script, @"\bPREPARE cw_p2n_").Count != 3 ||
            Regex.Matches(script, @"\bEXECUTE cw_p2n_").Count != 3 ||
            !script.Contains("SET ROLE chargewatch_read;", StringComparison.Ordinal) ||
            !script.Contains("REPEATABLE READ READ ONLY", StringComparison.Ordinal) ||
            !script.Contains("statement_timeout = '5s'", StringComparison.Ordinal) ||
            !script.Contains("ROLLBACK;", StringComparison.Ordinal) ||
            !script.Contains("RESET ROLE;", StringComparison.Ordinal) ||
            Regex.IsMatch(script, @"\b(INSERT|UPDATE|DELETE|ALTER|DROP|CREATE|TRUNCATE)\b",
                RegexOptions.IgnoreCase))
            throw new InvalidOperationException("Generated phase C NAS batch failed safety checks.");
        return script;
    }

    private static void EmitScenario(StringBuilder output, Scenario scenario)
    {
        if (!MapFilter.TryParse(new QueryCollection(scenario.FilterQuery),
            out var filters, out var error))
            throw new InvalidOperationException(error);

        var chosen = MapOverviewGridSelector.Select(scenario.Bbox, scenario.Zoom);
        if (chosen.Coverage.CellCount > MapGrid.InitialMarkerBudget)
            throw new InvalidOperationException("Grid footprint exceeds marker budget.");

        var g = chosen.Coverage;
        var sw = MapGrid.Bounds(new MapGridCell(chosen.Level, g.MinCellX, g.MaxCellY));
        var ne = MapGrid.Bounds(new MapGridCell(chosen.Level, g.MaxCellX, g.MinCellY));

        using var command = new NpgsqlCommand();
        Add(command, NpgsqlDbType.Double, sw.MinLon);
        Add(command, NpgsqlDbType.Double, sw.MinLat);
        Add(command, NpgsqlDbType.Double, ne.MaxLon);
        Add(command, NpgsqlDbType.Double, ne.MaxLat);
        Add(command, NpgsqlDbType.Double, MapGrid.WorldMeters / (1L << chosen.Level));
        Add(command, NpgsqlDbType.Bigint, g.MinCellX);
        Add(command, NpgsqlDbType.Bigint, g.MaxCellX);
        Add(command, NpgsqlDbType.Bigint, g.MinCellY);
        Add(command, NpgsqlDbType.Bigint, g.MaxCellY);
        filters.AddParameters(command);

        var productionSql = MapOverviewSql.Build(filters, constrainToCoverage: true).Trim().TrimEnd(';');
        var references = Regex.Matches(productionSql, @"\$(\d+)")
            .Select(m => int.Parse(m.Groups[1].Value))
            .Distinct().OrderBy(n => n).ToArray();
        if (!references.SequenceEqual(Enumerable.Range(1, command.Parameters.Count)) ||
            !productionSql.StartsWith("WITH selected AS MATERIALIZED (", StringComparison.Ordinal) ||
            !productionSql.Contains("s.cx BETWEEN $6 AND $7", StringComparison.Ordinal) ||
            !productionSql.Contains("s.cy BETWEEN $8 AND $9", StringComparison.Ordinal) ||
            Regex.IsMatch(productionSql, @"\bLIMIT\b", RegexOptions.IgnoreCase))
            throw new InvalidOperationException("Production SQL or positional contract unexpectedly changed.");

        var parameterTypes = new List<string>();
        var values = new List<string>();
        foreach (NpgsqlParameter p in command.Parameters)
        {
            switch (p.NpgsqlDbType)
            {
                case NpgsqlDbType.Double:
                    parameterTypes.Add("double precision");
                    values.Add(((double)p.Value!).ToString("R", CultureInfo.InvariantCulture));
                    break;
                case NpgsqlDbType.Bigint:
                    parameterTypes.Add("bigint");
                    values.Add(((long)p.Value!).ToString(CultureInfo.InvariantCulture));
                    break;
                case NpgsqlDbType.Array | NpgsqlDbType.Text:
                    parameterTypes.Add("text[]");
                    var strings = (string[])p.Value!;
                    values.Add("ARRAY[" + string.Join(",", strings.Select(s =>
                        "'" + s.Replace("'", "''") + "'")) + "]::text[]");
                    break;
                default:
                    throw new InvalidOperationException("Unexpected parameter type in phase-C SQL.");
            }
        }

        var preparedName = "cw_p2n_" + scenario.Name.ToLowerInvariant();
        output.AppendLine();
        output.AppendLine($"-- {scenario.Name}: zoom={scenario.Zoom}, effectiveLevel={chosen.Level}, coverageCells={g.CellCount}, bbox={scenario.Bbox}");
        output.AppendLine($"PREPARE {preparedName} ({string.Join(", ",parameterTypes)}) AS");
        output.AppendLine("WITH overview AS MATERIALIZED (");
        output.AppendLine(productionSql);
        output.AppendLine(")");
        output.AppendLine("SELECT count(*)::bigint AS marker_count,");
        output.AppendLine("       coalesce(sum(location_count),0)::bigint AS represented_locations,");
        output.AppendLine("       coalesce(sum(locations_with_free),0)::bigint AS green_locations,");
        output.AppendLine("       coalesce(sum(locations_with_no_free),0)::bigint AS red_locations,");
        output.AppendLine("       coalesce(sum(locations_unknown),0)::bigint AS unknown_locations,");
        output.AppendLine("       coalesce(bool_and(");
        output.AppendLine("           locations_with_free + locations_with_no_free + locations_unknown = location_count");
        output.AppendLine("       ),true) AS status_partition_valid,");
        output.AppendLine("       count(*) <= 500 AS marker_budget_valid");
        output.AppendLine("FROM overview;");
        output.AppendLine(@"\echo === " + scenario.Name + $" effectiveLevel={chosen.Level} coverageCells={g.CellCount} ===");
        output.AppendLine($"EXECUTE {preparedName} (");
        output.AppendLine("  " + string.Join(",\n  ", values));
        output.AppendLine(");");
        output.AppendLine($"DEALLOCATE {preparedName};");
    }

    private static void Add(NpgsqlCommand command, NpgsqlDbType type, object value) =>
        command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType = type, Value = value });
}
