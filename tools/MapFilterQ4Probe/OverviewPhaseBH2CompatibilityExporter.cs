using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using System.Text.RegularExpressions;

// P2m-H2: check corrected PRODUCTION per_location rows against current
// ChargeWatch /map/viewport Android counts and color, by exact location ID.
// The production CTE is mechanically extracted from MapOverviewSql.Build.
// Never use the obsolete P2l status-classification benchmark as baseline here.
internal static class OverviewPhaseBH2CompatibilityExporter
{
    public static string Create(string fixture)
    {
        if (fixture is not ("unfiltered" or "ccs2_dc"))
            throw new ArgumentOutOfRangeException(nameof(fixture));

        var filtered = fixture == "ccs2_dc";
        var filter = MapFilter.None;
        if (filtered && !MapFilter.TryParse(
            new QueryCollection(new Dictionary<string, StringValues>
            {
                ["connectorType"] = "ccs2", ["mode"] = "dc"
            }), out filter, out var error))
            throw new InvalidOperationException(error);

        var productionSql = MapOverviewSql.Build(filter).Trim().TrimEnd(';');
        const string gridSelect = "SELECT cx,cy,count(*)::int AS location_count,";
        var cut = productionSql.IndexOf(gridSelect, StringComparison.Ordinal);
        if (!productionSql.StartsWith("WITH selected AS MATERIALIZED (",
                StringComparison.Ordinal) ||
            cut < 0 ||
            productionSql.Split(gridSelect, StringSplitOptions.None).Length != 2 ||
            !productionSql.Contains("count(*) FILTER (WHERE free_evses>0)",
                StringComparison.Ordinal) ||
            !productionSql.Contains("count(*) FILTER (WHERE free_evses=0 AND total_evses>0)",
                StringComparison.Ordinal) ||
            !productionSql.Contains("count(*) FILTER (WHERE total_evses=0)",
                StringComparison.Ordinal) ||
            !productionSql.Contains("WHERE upper(e.status)='AVAILABLE'",
                StringComparison.Ordinal) ||
            productionSql.Contains("unclassified_evses", StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected corrected production SQL shape.");

        var ctes = productionSql[..cut].TrimEnd();
        if (!ctes.EndsWith(")", StringComparison.Ordinal) ||
            !ctes.Contains("), per_location AS (", StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected production per_location CTE.");

        var parameters = Regex.Matches(ctes, @"\$(\d+)")
            .Select(m => int.Parse(m.Groups[1].Value))
            .Distinct().Order().ToArray();
        if (!parameters.SequenceEqual(Enumerable.Range(1, filtered ? 7 : 5)))
            throw new InvalidOperationException("Changed MapFilter parameter contract.");

        var parameterTypes = filtered
            ? "double precision, double precision, double precision, double precision, double precision, text[], text[]"
            : "double precision, double precision, double precision, double precision, double precision";
        var parameterValues = filtered
            ? """
                  2.8124999999999907, 49.83798245308486,
                  8.437500000000005, 54.162433968067816,
                  156543.03392804097,
                  ARRAY['ccs2']::text[], ARRAY['dc']::text[]
              """
            : """
                  2.8124999999999907, 49.83798245308486,
                  8.437500000000005, 54.162433968067816,
                  156543.03392804097
              """;

        var sql = """
            -- P2m-H2: ONE bounded read-only legacy color/count parity
            -- Fixture: __FIXTURE__; exact PRODUCTION selected + per_location SQL.
            -- Per-location status is compared against existing ChargeWatch Android.
            -- A join directly against active EVSEs has one row per EVSE ID;
            -- count(DISTINCT e.id) in the legacy expression preserves its meaning.
            SET ROLE chargewatch_read;
            BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
            SET LOCAL statement_timeout = '5s';
            SET LOCAL lock_timeout = '1s';
            SET LOCAL jit = off;

            PREPARE cw_phaseb_h2(__PARAM_TYPES__) AS
            __PRODUCTION_CTES__,
            legacy_per_location AS MATERIALIZED (
                SELECT s.id,
                       count(DISTINCT e.id) FILTER (
                           WHERE upper(e.status) = 'AVAILABLE'
                       ) AS old_available,
                       count(DISTINCT e.id) FILTER (
                           WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('REMOVED','PLANNED')
                       ) AS old_total
                FROM selected s
                LEFT JOIN evse e ON e.location_id = s.id AND e.is_active
                GROUP BY s.id
            ),
            compared AS MATERIALIZED (
                SELECT p.id AS new_id, o.id AS old_id,
                       p.total_evses AS new_total,
                       p.free_evses AS new_available,
                       o.old_total, o.old_available,
                       CASE WHEN p.free_evses>0 THEN 'green'
                            WHEN p.total_evses>0 THEN 'red'
                            ELSE 'unknown' END AS new_color,
                       CASE WHEN o.old_available>0 THEN 'green'
                            WHEN o.old_total>0 THEN 'red'
                            ELSE 'unknown' END AS old_color
                FROM per_location p FULL JOIN legacy_per_location o ON o.id=p.id
            )
            SELECT
                count(*) AS compared_rows,
                count(*) FILTER (WHERE new_id IS NULL OR old_id IS NULL) AS missing_location_ids,
                count(*) FILTER (WHERE old_color='green') AS old_green,
                count(*) FILTER (WHERE old_color='red') AS old_red,
                count(*) FILTER (WHERE old_color='unknown') AS old_unknown,
                count(*) FILTER (WHERE new_color='green') AS new_green,
                count(*) FILTER (WHERE new_color='red') AS new_red,
                count(*) FILTER (WHERE new_color='unknown') AS new_unknown,
                count(*) FILTER (WHERE new_color IS DISTINCT FROM old_color) AS changed_color,
                count(*) FILTER (
                    WHERE new_available IS DISTINCT FROM old_available
                ) AS available_count_differences,
                count(*) FILTER (
                    WHERE new_total IS DISTINCT FROM old_total
                ) AS total_count_differences,
                count(*) FILTER (
                    WHERE old_total=0 AND old_available=0
                ) AS zero_zero_locations,
                count(*) FILTER (
                    WHERE new_total=0 AND new_available=0
                      AND new_color='unknown'
                ) AS correctly_unknown_zero_zero,
                count(*) FILTER (
                    WHERE new_available<0 OR new_total<0
                       OR new_available>new_total
                ) AS invalid_new_counts,
                (
                    count(*)>0
                    AND count(*) FILTER (WHERE new_id IS NULL OR old_id IS NULL)=0
                    AND count(*) FILTER (
                        WHERE new_color IS DISTINCT FROM old_color
                    )=0
                    AND count(*) FILTER (
                        WHERE new_available IS DISTINCT FROM old_available
                    )=0
                    AND count(*) FILTER (
                        WHERE new_total IS DISTINCT FROM old_total
                    )=0
                    AND count(*) FILTER (
                        WHERE new_available<0 OR new_total<0
                           OR new_available>new_total
                    )=0
                    AND count(*) FILTER (
                        WHERE old_total=0 AND old_available=0
                    )=count(*) FILTER (
                        WHERE new_total=0 AND new_available=0
                          AND new_color='unknown'
                    )
                ) AS marker_compatibility_matches
            FROM compared;

            EXECUTE cw_phaseb_h2(__PARAM_VALUES__);

            DEALLOCATE cw_phaseb_h2;
            ROLLBACK;
            RESET ROLE;
            """
            .Replace("__FIXTURE__", fixture, StringComparison.Ordinal)
            .Replace("__PRODUCTION_CTES__", ctes, StringComparison.Ordinal)
            .Replace("__PARAM_TYPES__", parameterTypes, StringComparison.Ordinal)
            .Replace("__PARAM_VALUES__", parameterValues, StringComparison.Ordinal);

        if (sql.Contains("__PRODUCTION_CTES__", StringComparison.Ordinal) ||
            sql.Contains("__PARAM_TYPES__", StringComparison.Ordinal) ||
            sql.Contains("__PARAM_VALUES__", StringComparison.Ordinal) ||
            !sql.Contains("P2m-H2: ONE bounded read-only", StringComparison.Ordinal) ||
            !sql.Contains("SET ROLE chargewatch_read;", StringComparison.Ordinal) ||
            !sql.Contains("REPEATABLE READ READ ONLY", StringComparison.Ordinal) ||
            !sql.Contains("statement_timeout = '5s'", StringComparison.Ordinal) ||
            !sql.Contains("lock_timeout = '1s'", StringComparison.Ordinal) ||
            Regex.Matches(sql, @"\bPREPARE cw_phaseb_h2\(").Count != 1 ||
            Regex.Matches(sql, @"\bEXECUTE cw_phaseb_h2\(").Count != 1 ||
            !sql.Contains("ROLLBACK;", StringComparison.Ordinal) ||
            sql.Contains("EXPLAIN (ANALYZE", StringComparison.Ordinal))
            throw new InvalidOperationException("Unsafe or malformed P2m-H2 SQL export.");

        return sql;
    }
}
