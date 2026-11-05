using System.Text.RegularExpressions;

// HISTORICAL P2m-H1: phaseb_color below intentionally models the old,
// pre-compatibility-fix unclassified-status override. Use H2 after the fix.
// P2m-H1: one read-only national current-API vs phase-B singleton
// marker count/color audit. Selection is copied mechanically from the
// linked PRODUCTION MapOverviewSql.Build(MapFilter.None) SQL.
// We do not copy location-filter business logic or alter either endpoint.
// Old API uses DISTINCT e.id after connector LEFT JOIN; here the LEFT JOIN
// is against EVSE only, so EVSE primary keys are unique before aggregation.
internal static class OverviewPhaseBHMarkerSemanticsExporter
{
    public static string Create()
    {
        var query = MapOverviewSql.Build(MapFilter.None).Trim().TrimEnd(';');
        const string split = "), per_location AS (";
        if (!query.StartsWith("WITH selected AS MATERIALIZED (", StringComparison.Ordinal) ||
            query.Split(split, StringSplitOptions.None).Length != 2 ||
            Regex.Matches(query, @"\$(\d+)").Select(m => int.Parse(m.Groups[1].Value))
                .Distinct().Order().SequenceEqual(Enumerable.Range(1, 5)) == false)
            throw new InvalidOperationException("Unexpected production unfiltered selection layout.");

        var selection = query[..query.IndexOf(split, StringComparison.Ordinal)] + ")";
        // One materialized selected set and one EVSE aggregation. Phase B's
        // existing WHERE predicates are retained verbatim, with real binds.
        var sql = """
            -- P2m-H1: ONE bounded read-only national legacy/phase-B marker semantics audit
            -- Probe compares actual ChargeWatch viewport counts, not NDW GeoJSON fallback.
            -- Each selected charging_location has at most one EVSE row per EVSE primary key.
            -- The viewport's connector LEFT JOIN can multiply these rows but its
            -- count(DISTINCT e.id) restores exactly the same count.
            -- With the original ChargeWatch Android parser, 0/0 is gray with label 0/0;
            -- phase-B unknown singleton returns null counts; new Android presentation TBD.
            SET ROLE chargewatch_read;
            BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
            SET LOCAL statement_timeout = '5s';
            SET LOCAL lock_timeout = '1s';
            SET LOCAL jit = off;

            PREPARE cw_phaseb_h1(
                double precision, double precision, double precision,
                double precision, double precision
            ) AS
            __SELECTION__,
            per_location AS MATERIALIZED (
                SELECT s.id,
                       count(DISTINCT e.id) FILTER (
                           WHERE upper(e.status) = 'AVAILABLE'
                       ) AS legacy_available,
                       count(DISTINCT e.id) FILTER (
                           WHERE upper(btrim(coalesce(e.status,'')))
                                 NOT IN ('REMOVED','PLANNED')
                       ) AS legacy_total,
                       count(e.id) FILTER (
                           WHERE upper(btrim(coalesce(e.status,''))) = 'AVAILABLE'
                       ) AS phaseb_available,
                       count(e.id) FILTER (
                           WHERE upper(btrim(coalesce(e.status,'')))
                                 NOT IN ('REMOVED','PLANNED')
                       ) AS phaseb_total,
                       count(e.id) FILTER (
                           WHERE upper(btrim(coalesce(e.status,''))) NOT IN
                             ('AVAILABLE','CHARGING','BLOCKED','RESERVED',
                              'INOPERATIVE','OUTOFORDER','PLANNED','REMOVED')
                       ) AS unclassified_evses,
                       count(e.id) FILTER (WHERE e.status IS NULL) AS null_status_evses,
                       count(e.id) FILTER (
                           WHERE e.status IS NOT NULL
                             AND e.status <> btrim(e.status)
                       ) AS whitespace_status_evses
                FROM selected s
                LEFT JOIN evse e ON e.location_id = s.id AND e.is_active
                GROUP BY s.id
            ),
            classified AS MATERIALIZED (
                SELECT *,
                       CASE
                         WHEN legacy_available > 0 THEN 'green'
                         WHEN legacy_total > 0 THEN 'red'
                         ELSE 'unknown'
                       END AS legacy_color,
                       CASE
                         WHEN phaseb_total = 0 OR unclassified_evses > 0 THEN 'unknown'
                         WHEN phaseb_available > 0 THEN 'green'
                         ELSE 'red'
                       END AS phaseb_color
                FROM per_location
            )
            SELECT
                count(*) AS audited_locations,
                count(*) FILTER (WHERE legacy_color='green') AS legacy_green,
                count(*) FILTER (WHERE legacy_color='red') AS legacy_red,
                count(*) FILTER (WHERE legacy_color='unknown') AS legacy_unknown,
                count(*) FILTER (WHERE phaseb_color='green') AS phaseb_green,
                count(*) FILTER (WHERE phaseb_color='red') AS phaseb_red,
                count(*) FILTER (WHERE phaseb_color='unknown') AS phaseb_unknown,
                count(*) FILTER (WHERE legacy_color <> phaseb_color) AS changed_color,
                count(*) FILTER (
                    WHERE legacy_color='green' AND phaseb_color='unknown'
                ) AS green_to_unknown,
                count(*) FILTER (
                    WHERE legacy_color='red' AND phaseb_color='unknown'
                ) AS red_to_unknown,
                count(*) FILTER (
                    WHERE legacy_color='red' AND phaseb_color='green'
                ) AS red_to_green,
                count(*) FILTER (
                    WHERE legacy_color='green' AND phaseb_color='red'
                ) AS green_to_red,
                count(*) FILTER (
                    WHERE legacy_color='unknown' AND phaseb_color <> 'unknown'
                ) AS unknown_to_known,
                count(*) FILTER (
                    WHERE legacy_available <> phaseb_available
                ) AS available_count_differences,
                count(*) FILTER (
                    WHERE legacy_total <> phaseb_total
                ) AS total_count_differences,
                count(*) FILTER (
                    WHERE unclassified_evses > 0
                ) AS locations_with_unclassified_evses,
                count(*) FILTER (
                    WHERE unclassified_evses > 0 AND legacy_available > 0
                ) AS unclassified_and_legacy_free,
                count(*) FILTER (
                    WHERE null_status_evses > 0
                ) AS locations_with_null_status,
                count(*) FILTER (
                    WHERE whitespace_status_evses > 0
                ) AS locations_with_whitespace_status,
                count(*) FILTER (
                    WHERE legacy_total = 0
                ) AS legacy_zero_total_new_null_counts,
                bool_and(
                    legacy_available <= legacy_total
                    AND phaseb_available <= phaseb_total
                    AND legacy_total = phaseb_total
                ) AS count_invariants_match
            FROM classified;

            EXECUTE cw_phaseb_h1(
                2.8124999999999907,
                49.83798245308486,
                8.437500000000005,
                54.162433968067816,
                156543.03392804097
            );

            DEALLOCATE cw_phaseb_h1;
            ROLLBACK;
            RESET ROLE;
            """.Replace("__SELECTION__", selection, StringComparison.Ordinal);

        if (sql.Contains("__SELECTION__", StringComparison.Ordinal) ||
            !sql.Contains("P2m-H1: ONE bounded read-only", StringComparison.Ordinal) ||
            !sql.Contains("SET ROLE chargewatch_read;", StringComparison.Ordinal) ||
            !sql.Contains("REPEATABLE READ READ ONLY", StringComparison.Ordinal) ||
            !sql.Contains("statement_timeout = '5s'", StringComparison.Ordinal) ||
            !sql.Contains("lock_timeout = '1s'", StringComparison.Ordinal) ||
            Regex.Matches(sql, @"\bPREPARE cw_phaseb_h1\(").Count != 1 ||
            Regex.Matches(sql, @"\bEXECUTE cw_phaseb_h1\(").Count != 1 ||
            !sql.Contains("ROLLBACK;", StringComparison.Ordinal) ||
            sql.Contains("EXPLAIN (ANALYZE", StringComparison.Ordinal))
            throw new InvalidOperationException("Unsafe or malformed P2m-H1 SQL export.");

        return sql;
    }
}
