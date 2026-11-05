using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using System.Text.RegularExpressions;

// HISTORICAL P2m-G2: its P2l benchmark uses the PRE-FIX unclassified-status rule.
// After the H1 compatibility fix it is expected to differ from production
// for affected colors. Use P2m-H2 for post-fix acceptance instead.
// P2m-G2: bounded same-snapshot comparison of the six grid fields produced
// by the validated national P2l reference and the actual phase-B SQL.
// Exactly two grid aggregations, no duplicate ID-only branches.
// Production MapFilter.cs and MapOverviewSql.Build are linked into this probe.
internal static class OverviewPhaseBGGridParityExporter
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

        var baseline = OverviewNationalBaselineExporter.Create("ccs2_dc");
        const string explain = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS)";
        var begin = baseline.IndexOf(explain, StringComparison.Ordinal);
        var end = baseline.IndexOf("ROLLBACK;", begin, StringComparison.Ordinal);
        if (begin < 0 || end < 0)
            throw new InvalidOperationException("P2l reference markers changed.");

        // The reference was previously verified by P2l. It returns exactly
        // (cx,cy,location_count,locations_with_free,locations_with_no_free,
        // locations_unknown). Keep its original grid and MapFilter predicate.
        var original = baseline[(begin + explain.Length)..end].Trim().TrimEnd(';');
        var phaseB = MapOverviewSql.Build(filter).Trim().TrimEnd(';');
        if (!original.StartsWith("WITH grid AS (", StringComparison.Ordinal) ||
            !phaseB.StartsWith("WITH selected AS MATERIALIZED (", StringComparison.Ordinal) ||
            !original.Contains("ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326)",
                StringComparison.Ordinal) ||
            !original.Contains("40075016.68557849::double precision / 256.0",
                StringComparison.Ordinal) ||
            !original.Contains("GROUP BY cx,cy", StringComparison.Ordinal) ||
            !phaseB.Contains("GROUP BY cx,cy", StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected reference or phase-B SQL layout.");

        var parameters = Regex.Matches(phaseB, @"\$(\d+)")
            .Select(m => int.Parse(m.Groups[1].Value))
            .Distinct().Order().ToArray();
        if (!parameters.SequenceEqual(Enumerable.Range(1, 7)) ||
            Regex.IsMatch(original, @"\$[1-9]") ||
            original.Contains("EXPLAIN", StringComparison.OrdinalIgnoreCase) ||
            phaseB.Contains("EXPLAIN", StringComparison.OrdinalIgnoreCase) ||
            original.Contains("INSERT", StringComparison.OrdinalIgnoreCase) ||
            phaseB.Contains("INSERT", StringComparison.OrdinalIgnoreCase) ||
            original.Contains("DELETE FROM", StringComparison.OrdinalIgnoreCase) ||
            phaseB.Contains("DELETE FROM", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Unexpected SQL or parameter contract.");

        // Cast the new API's integer count columns to bigint for EXCEPT
        // type parity with the P2l reference, without changing either query.
        var sql = """
            -- P2m-G2: ONE bounded read-only same-snapshot full grid/status parity
            -- P2l national CCS2/DC grid8 versus actual parameterized MapOverviewSql
            SET ROLE chargewatch_read;
            BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
            SET LOCAL statement_timeout = '5s';
            SET LOCAL lock_timeout = '1s';
            SET LOCAL jit = off;

            PREPARE cw_phaseb_g2(
                double precision, double precision, double precision,
                double precision, double precision, text[], text[]
            ) AS
            WITH original_grid AS MATERIALIZED (
                __ORIGINAL_GRID__
            ),
            phaseb_grid AS MATERIALIZED (
                SELECT cx, cy, location_count::bigint AS location_count,
                       locations_with_free::bigint AS locations_with_free,
                       locations_with_no_free::bigint AS locations_with_no_free,
                       locations_unknown::bigint AS locations_unknown
                FROM (__PHASEB_GRID__) AS phaseb_result
            ),
            original_only AS MATERIALIZED (
                SELECT cx,cy,location_count,locations_with_free,
                       locations_with_no_free,locations_unknown
                FROM original_grid
                EXCEPT
                SELECT cx,cy,location_count,locations_with_free,
                       locations_with_no_free,locations_unknown
                FROM phaseb_grid
            ),
            phaseb_only AS MATERIALIZED (
                SELECT cx,cy,location_count,locations_with_free,
                       locations_with_no_free,locations_unknown
                FROM phaseb_grid
                EXCEPT
                SELECT cx,cy,location_count,locations_with_free,
                       locations_with_no_free,locations_unknown
                FROM original_grid
            ),
            original_quality AS (
                SELECT count(*) FILTER (
                    WHERE location_count <= 0 OR location_count IS NULL
                       OR locations_with_free IS NULL
                       OR locations_with_no_free IS NULL
                       OR locations_unknown IS NULL
                       OR locations_with_free < 0 OR locations_with_no_free < 0
                       OR locations_unknown < 0
                       OR locations_with_free + locations_with_no_free
                          + locations_unknown IS DISTINCT FROM location_count
                ) AS invalid_partitions
                FROM original_grid
            ),
            phaseb_quality AS (
                SELECT count(*) FILTER (
                    WHERE location_count <= 0 OR location_count IS NULL
                       OR locations_with_free IS NULL
                       OR locations_with_no_free IS NULL
                       OR locations_unknown IS NULL
                       OR locations_with_free < 0 OR locations_with_no_free < 0
                       OR locations_unknown < 0
                       OR locations_with_free + locations_with_no_free
                          + locations_unknown IS DISTINCT FROM location_count
                ) AS invalid_partitions
                FROM phaseb_grid
            )
            SELECT
                (SELECT count(*) FROM original_grid) AS original_cells,
                (SELECT count(*) FROM phaseb_grid) AS phaseb_cells,
                (SELECT coalesce(sum(location_count),0) FROM original_grid)
                    AS original_locations,
                (SELECT coalesce(sum(location_count),0) FROM phaseb_grid)
                    AS phaseb_locations,
                (SELECT count(*) FROM original_only) AS original_only_grid_rows,
                (SELECT count(*) FROM phaseb_only) AS phaseb_only_grid_rows,
                (SELECT count(*) FROM (
                    SELECT cx,cy FROM original_grid GROUP BY cx,cy HAVING count(*) > 1
                ) AS duplicate_original) AS original_duplicate_cells,
                (SELECT count(*) FROM (
                    SELECT cx,cy FROM phaseb_grid GROUP BY cx,cy HAVING count(*) > 1
                ) AS duplicate_phaseb) AS phaseb_duplicate_cells,
                (SELECT invalid_partitions FROM original_quality)
                    AS original_invalid_partitions,
                (SELECT invalid_partitions FROM phaseb_quality)
                    AS phaseb_invalid_partitions,
                (
                    (SELECT count(*) FROM original_grid) > 0
                    AND (SELECT count(*) FROM original_grid) =
                        (SELECT count(*) FROM phaseb_grid)
                    AND (SELECT coalesce(sum(location_count),0) FROM original_grid) =
                        (SELECT coalesce(sum(location_count),0) FROM phaseb_grid)
                    AND (SELECT invalid_partitions FROM original_quality) = 0
                    AND (SELECT invalid_partitions FROM phaseb_quality) = 0
                    AND NOT EXISTS (
                        SELECT 1 FROM original_grid GROUP BY cx,cy HAVING count(*) > 1
                    )
                    AND NOT EXISTS (
                        SELECT 1 FROM phaseb_grid GROUP BY cx,cy HAVING count(*) > 1
                    )
                    AND NOT EXISTS (SELECT 1 FROM original_only)
                    AND NOT EXISTS (SELECT 1 FROM phaseb_only)
                ) AS grid_parity_matches;

            EXECUTE cw_phaseb_g2(
                2.8124999999999907,
                49.83798245308486,
                8.437500000000005,
                54.162433968067816,
                156543.03392804097,
                ARRAY['ccs2']::text[],
                ARRAY['dc']::text[]
            );

            DEALLOCATE cw_phaseb_g2;
            ROLLBACK;
            RESET ROLE;
            """.Replace("__ORIGINAL_GRID__", original, StringComparison.Ordinal)
               .Replace("__PHASEB_GRID__", phaseB, StringComparison.Ordinal);

        if (!sql.Contains("P2m-G2: ONE bounded read-only", StringComparison.Ordinal) ||
            Regex.Matches(sql, @"\bPREPARE cw_phaseb_g2\(").Count != 1 ||
            Regex.Matches(sql, @"\bEXECUTE cw_phaseb_g2\(").Count != 1 ||
            !sql.Contains("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY",
                StringComparison.Ordinal) ||
            !sql.Contains("SET ROLE chargewatch_read;", StringComparison.Ordinal) ||
            !sql.Contains("statement_timeout = '5s'", StringComparison.Ordinal) ||
            !sql.Contains("lock_timeout = '1s'", StringComparison.Ordinal) ||
            !sql.Contains("ROLLBACK;", StringComparison.Ordinal) ||
            sql.Contains("__ORIGINAL_GRID__", StringComparison.Ordinal) ||
            sql.Contains("__PHASEB_GRID__", StringComparison.Ordinal) ||
            sql.Contains("EXPLAIN (ANALYZE", StringComparison.Ordinal))
            throw new InvalidOperationException("Unsafe or incomplete P2m-G2 SQL export.");

        return sql;
    }
}
