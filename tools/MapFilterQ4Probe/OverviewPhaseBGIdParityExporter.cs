using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using System.Text.RegularExpressions;

// P2m-G1: compare only selected (id,cx,cy) between validated P2l
// and the exact phase-B MapOverviewSql selected CTE. A single read-only,
// prepared SELECT evaluates the two selections once in one DB snapshot.
// Keep the actual phase-B bbox/filter arguments as bound PG parameters.
internal static class OverviewPhaseBGIdParityExporter
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
            throw new InvalidOperationException("P2l baseline markers changed.");
        var original = baseline[(begin + explain.Length)..end].Trim().TrimEnd(';');
        var phaseB = MapOverviewSql.Build(filter).Trim().TrimEnd(';');

        const string split = "), per_location AS (";
        if (!original.StartsWith("WITH grid AS (", StringComparison.Ordinal) ||
            !phaseB.StartsWith("WITH selected AS MATERIALIZED (", StringComparison.Ordinal) ||
            original.Split(split, StringSplitOptions.None).Length != 2 ||
            phaseB.Split(split, StringSplitOptions.None).Length != 2 ||
            !original.Contains("ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326)",
                StringComparison.Ordinal) ||
            !original.Contains("40075016.68557849::double precision / 256.0",
                StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected reference or phase-B SQL CTE layout.");

        // Use the production-generated predicates unchanged. Only omit
        // per_location/status aggregation for this first correctness gate.
        var originalIds = original[..original.IndexOf(split, StringComparison.Ordinal)] +
                          ") SELECT id,cx,cy FROM selected";
        var phaseBIds = phaseB[..phaseB.IndexOf(split, StringComparison.Ordinal)] +
                        ") SELECT id,cx,cy FROM selected";

        var parameters = Regex.Matches(phaseBIds, @"\$(\d+)")
            .Select(m => int.Parse(m.Groups[1].Value))
            .Distinct().Order().ToArray();
        if (!parameters.SequenceEqual(Enumerable.Range(1, 7)) ||
            Regex.IsMatch(originalIds, @"\$[1-9]") ||
            originalIds.Contains("INSERT", StringComparison.OrdinalIgnoreCase) ||
            phaseBIds.Contains("INSERT", StringComparison.OrdinalIgnoreCase) ||
            phaseBIds.Contains("DELETE FROM", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Unexpected SQL or parameter contract.");

        var sql = """
            -- P2m-G1: ONE bounded read-only same-snapshot ID/cell parity check
            -- P2l reference versus phase-B MapOverviewSql.Build(MapFilter), CCS2/DC, national grid8
            SET ROLE chargewatch_read;
            BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
            SET LOCAL statement_timeout = '5s';
            SET LOCAL lock_timeout = '1s';
            SET LOCAL jit = off;

            PREPARE cw_phaseb_g1(
                double precision, double precision, double precision,
                double precision, double precision, text[], text[]
            ) AS
            WITH original_ids AS MATERIALIZED (
                __ORIGINAL_IDS__
            ),
            phaseb_ids AS MATERIALIZED (
                __PHASEB_IDS__
            ),
            original_only AS MATERIALIZED (
                SELECT id,cx,cy FROM original_ids
                EXCEPT
                SELECT id,cx,cy FROM phaseb_ids
            ),
            phaseb_only AS MATERIALIZED (
                SELECT id,cx,cy FROM phaseb_ids
                EXCEPT
                SELECT id,cx,cy FROM original_ids
            )
            SELECT
                (SELECT count(*) FROM original_ids) AS original_locations,
                (SELECT count(DISTINCT id) FROM original_ids) AS original_distinct_ids,
                (SELECT count(*) FROM phaseb_ids) AS phaseb_locations,
                (SELECT count(DISTINCT id) FROM phaseb_ids) AS phaseb_distinct_ids,
                (SELECT count(*) FROM original_only) AS original_only_id_cells,
                (SELECT count(*) FROM phaseb_only) AS phaseb_only_id_cells,
                (SELECT count(*) FROM original_ids WHERE id IS NULL OR cx IS NULL OR cy IS NULL)
                  + (SELECT count(*) FROM phaseb_ids WHERE id IS NULL OR cx IS NULL OR cy IS NULL)
                  AS invalid_assignments,
                (
                    (SELECT count(*) FROM original_ids) > 0
                    AND (SELECT count(*) FROM original_ids) =
                        (SELECT count(DISTINCT id) FROM original_ids)
                    AND (SELECT count(*) FROM phaseb_ids) =
                        (SELECT count(DISTINCT id) FROM phaseb_ids)
                    AND (SELECT count(*) FROM original_ids) =
                        (SELECT count(*) FROM phaseb_ids)
                    AND NOT EXISTS (SELECT 1 FROM original_only)
                    AND NOT EXISTS (SELECT 1 FROM phaseb_only)
                    AND NOT EXISTS (
                        SELECT 1 FROM original_ids WHERE id IS NULL OR cx IS NULL OR cy IS NULL
                    )
                    AND NOT EXISTS (
                        SELECT 1 FROM phaseb_ids WHERE id IS NULL OR cx IS NULL OR cy IS NULL
                    )
                ) AS id_cell_parity_matches;

            EXECUTE cw_phaseb_g1(
                2.8124999999999907,
                49.83798245308486,
                8.437500000000005,
                54.162433968067816,
                156543.03392804097,
                ARRAY['ccs2']::text[],
                ARRAY['dc']::text[]
            );

            DEALLOCATE cw_phaseb_g1;
            ROLLBACK;
            RESET ROLE;
            """.Replace("__ORIGINAL_IDS__", originalIds, StringComparison.Ordinal)
               .Replace("__PHASEB_IDS__", phaseBIds, StringComparison.Ordinal);

        if (!sql.Contains("P2m-G1: ONE bounded read-only", StringComparison.Ordinal) ||
            Regex.Matches(sql, @"\bEXECUTE cw_phaseb_g1\(").Count != 1 ||
            Regex.Matches(sql, @"\bPREPARE cw_phaseb_g1\(").Count != 1 ||
            !sql.Contains("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY",
                StringComparison.Ordinal) ||
            !sql.Contains("SET ROLE chargewatch_read;", StringComparison.Ordinal) ||
            !sql.Contains("statement_timeout = '5s'", StringComparison.Ordinal) ||
            !sql.Contains("ROLLBACK;", StringComparison.Ordinal) ||
            sql.Contains("__ORIGINAL_IDS__", StringComparison.Ordinal) ||
            sql.Contains("__PHASEB_IDS__", StringComparison.Ordinal) ||
            sql.Contains("EXPLAIN (ANALYZE", StringComparison.Ordinal))
            throw new InvalidOperationException("Unsafe or incomplete P2m-G1 SQL export.");

        return sql;
    }
}
