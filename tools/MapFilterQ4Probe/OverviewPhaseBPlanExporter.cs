using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using System.Text.RegularExpressions;

// P2m diagnosis: EXPLAIN without ANALYZE for the *single* real phase-B
// SQL source. Do not parse P2m's multi-query output or duplicate the SQL.
internal static class OverviewPhaseBPlanExporter
{
    public static string Create()
    {
        var query = new QueryCollection(new Dictionary<string, StringValues>
        {
            ["connectorType"] = "ccs2",
            ["mode"] = "dc"
        });
        if (!MapFilter.TryParse(query, out var filter, out var error))
            throw new InvalidOperationException(error);

        // The same SQL builder is used by MapOverviewDatabase.ReadAsync.
        // Replace parameters with the fixed grid8 test fixture only.
        var phaseB = MapOverviewSql.Build(filter);
        var replacements = new Dictionary<int, string>
        {
            [1] = "(SELECT ST_XMin(shape::geometry) FROM phaseb_region)",
            [2] = "(SELECT ST_YMin(shape::geometry) FROM phaseb_region)",
            [3] = "(SELECT ST_XMax(shape::geometry) FROM phaseb_region)",
            [4] = "(SELECT ST_YMax(shape::geometry) FROM phaseb_region)",
            [5] = "(40075016.68557849::double precision / 256.0)",
            [6] = "ARRAY['ccs2']::text[]",
            [7] = "ARRAY['dc']::text[]"
        };
        phaseB = Regex.Replace(phaseB, @"\$(\d+)", m =>
        {
            var index = int.Parse(m.Groups[1].Value);
            return replacements.TryGetValue(index, out var replacement)
                ? replacement
                : throw new InvalidOperationException($"Unexpected SQL parameter: ${index}");
        });

        if (!phaseB.StartsWith("WITH selected AS MATERIALIZED (", StringComparison.Ordinal) ||
            !phaseB.Contains("ST_Intersects(l.position,ST_MakeEnvelope(", StringComparison.Ordinal) ||
            !phaseB.Contains("ARRAY['ccs2']::text[]", StringComparison.Ordinal) ||
            !phaseB.Contains("ARRAY['dc']::text[]", StringComparison.Ordinal) ||
            Regex.IsMatch(phaseB, @"\$\d+"))
            throw new InvalidOperationException("Unexpected phase-B SQL shape or unbound parameters.");

        var script = """
            -- P2m-DIAG: single actual phase-B national CCS2/DC planner only
            SET ROLE chargewatch_read;
            BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
            SET LOCAL statement_timeout = '5s';
            SET LOCAL lock_timeout = '1s';
            SET LOCAL jit = off;

            EXPLAIN (VERBOSE, SETTINGS)
            WITH phaseb_region AS MATERIALIZED (
                WITH grid AS (
                    SELECT 40075016.68557849::double precision / 256.0 AS cell_m,
                           ST_Transform(
                               ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326),3857
                           ) AS area
                ), bounds AS (
                    SELECT cell_m,
                           floor((ST_XMin(area)+20037508.342789244)/cell_m)::bigint AS min_x,
                           floor((ST_XMax(area)+20037508.342789244)/cell_m)::bigint AS max_x,
                           floor((20037508.342789244-ST_YMax(area))/cell_m)::bigint AS min_y,
                           floor((20037508.342789244-ST_YMin(area))/cell_m)::bigint AS max_y
                    FROM grid
                )
                SELECT ST_Transform(ST_MakeEnvelope(
                    -20037508.342789244+min_x*cell_m,
                     20037508.342789244-(max_y+1)*cell_m,
                    -20037508.342789244+(max_x+1)*cell_m,
                     20037508.342789244-min_y*cell_m,3857),4326)::geography AS shape
                FROM bounds
            ),
            phaseb_grid AS MATERIALIZED (
                __PHASEB__
            )
            SELECT cx,cy,location_count,locations_with_free,
                   locations_with_no_free,locations_unknown
            FROM phaseb_grid
            ORDER BY cy,cx;

            ROLLBACK;
            RESET ROLE;
            """.Replace("__PHASEB__", phaseB, StringComparison.Ordinal);

        if (Regex.Matches(script, @"EXPLAIN \(VERBOSE, SETTINGS\)").Count != 1 ||
            script.Contains("EXPLAIN (ANALYZE", StringComparison.Ordinal) ||
            Regex.IsMatch(script, @"\$\d+") ||
            !script.Contains("READ ONLY", StringComparison.Ordinal) ||
            !script.Contains("ROLLBACK;", StringComparison.Ordinal))
            throw new InvalidOperationException("P2m-DIAG safety check failed.");

        return script;
    }
}
