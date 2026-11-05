using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using System.Text.RegularExpressions;

// P2m-E: planner-only EXPLAIN EXECUTE of the production parameterized SQL.
// Unlike the earlier diagnostic, the geographical coordinates are actual
// bound parameters rather than run-time SQL InitPlan subqueries.
internal static class OverviewPhaseBPreparedPlanExporter
{
    public static string Create()
    {
        if (!MapFilter.TryParse(
            new QueryCollection(new Dictionary<string, StringValues>
            {
                ["connectorType"] = "ccs2",
                ["mode"] = "dc"
            }), out var filter, out var error))
            throw new InvalidOperationException(error);

        var query = MapOverviewSql.Build(filter).Trim().TrimEnd(';');
        var used = Regex.Matches(query, @"\$(\d+)")
            .Select(x => int.Parse(x.Groups[1].Value))
            .Distinct().OrderBy(x => x).ToArray();
        if (!used.SequenceEqual(Enumerable.Range(1, 7)) ||
            query.Contains("EXPLAIN", StringComparison.OrdinalIgnoreCase) ||
            query.Contains("INSERT", StringComparison.OrdinalIgnoreCase) ||
            query.Contains("UPDATE charging_location", StringComparison.OrdinalIgnoreCase) ||
            query.Contains("DELETE FROM", StringComparison.OrdinalIgnoreCase) ||
            !query.StartsWith("WITH selected AS MATERIALIZED (", StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected parameterized overview SQL shape.");

        // The canonical grid-8 envelope for the P2i/P2l fixture starts
        // with cells x=130..133, y=82..86. Values below are WGS84 outer
        // boundaries corresponding to that full cell range, not a partial
        // camera bbox and not a run-time scalar InitPlan.
        var sql = """
            -- P2m-E: planning-only PREPARE/EXPLAIN EXECUTE real phase-B bound parameters
            SET ROLE chargewatch_read;
            BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
            SET LOCAL statement_timeout = '5s';
            SET LOCAL lock_timeout = '1s';
            SET LOCAL jit = off;

            PREPARE cw_phaseb_planner(
                double precision, double precision, double precision,
                double precision, double precision, text[], text[]
            ) AS
            __QUERY__;

            EXPLAIN (VERBOSE, SETTINGS)
            EXECUTE cw_phaseb_planner(
                2.8124999999999907,
                49.83798245308486,
                8.437500000000005,
                54.162433968067816,
                156543.03392804097,
                ARRAY['ccs2']::text[],
                ARRAY['dc']::text[]
            );

            DEALLOCATE cw_phaseb_planner;
            ROLLBACK;
            RESET ROLE;
            """.Replace("__QUERY__", query, StringComparison.Ordinal);

        if (Regex.Matches(sql, @"EXPLAIN \(VERBOSE, SETTINGS\)").Count != 1 ||
            sql.Contains("EXPLAIN (ANALYZE", StringComparison.Ordinal) ||
            !sql.Contains("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY",
                StringComparison.Ordinal) ||
            !sql.Contains("statement_timeout = '5s'", StringComparison.Ordinal) ||
            !sql.Contains("ROLLBACK;", StringComparison.Ordinal) ||
            !sql.Contains("PREPARE cw_phaseb_planner", StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected prepared-plan diagnostic script.");
        return sql;
    }
}
