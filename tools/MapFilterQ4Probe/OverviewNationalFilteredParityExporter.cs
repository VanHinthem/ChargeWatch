// P2l: national CCS2/DC ID + full-cell aggregate parity. The reference
// starts with a distinct connector/EVSE qualification (not a geo-first scan).
// The original overview still comes from linked production MapFilter.cs.
// No DB connection in the exporter; all generated SQL is read-only.
internal static class OverviewNationalFilteredParityExporter
{
    public static string Create()
    {
        var full = OverviewNationalBaselineExporter.Create("ccs2_dc");
        const string explain = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS)";
        const string footer = "ROLLBACK;";
        var start = full.IndexOf(explain, StringComparison.Ordinal);
        var stop = full.IndexOf(footer, start, StringComparison.Ordinal);
        if (start < 0 || stop < 0)
            throw new InvalidOperationException("Missing original national SQL markers.");
        var overview = full[(start + explain.Length)..stop].Trim().TrimEnd(';');
        const string selectedEnd = "), per_location AS (";
        if (!overview.StartsWith("WITH grid AS (", StringComparison.Ordinal) ||
            overview.Split(selectedEnd, StringSplitOptions.None).Length != 2 ||
            !overview.Contains("ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326)", StringComparison.Ordinal) ||
            !overview.Contains("40075016.68557849::double precision / 256.0", StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected national MapFilter SQL layout.");
        var originalIds = overview[..overview.IndexOf(selectedEnd, StringComparison.Ordinal)] +
                          ") SELECT id,cx,cy FROM selected";

        // Technical reference is intentionally independent of MapFilter's
        // correlated EXISTS but equivalent specifically for CCS2 + DC.
        // It does not redefine the other MapFilter fixtures.
        var query = """
            SET ROLE chargewatch_read;
            BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
            SET LOCAL statement_timeout = '5s';
            SET LOCAL lock_timeout = '1s';
            SET LOCAL jit = off;

            WITH original_ids AS MATERIALIZED (
                __ORIGINAL_IDS__
            ),
            original_grid AS MATERIALIZED (
                __ORIGINAL_OVERVIEW__
            ),
            reference_ids AS MATERIALIZED (
                WITH grid AS (
                    SELECT 40075016.68557849::double precision / 256.0 AS cell_m,
                           ST_Transform(ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326),3857) AS area
                ),
                bounds AS (
                    SELECT g.cell_m,
                           floor((ST_XMin(g.area)+20037508.342789244)/g.cell_m)::bigint AS min_x,
                           floor((ST_XMax(g.area)+20037508.342789244)/g.cell_m)::bigint AS max_x,
                           floor((20037508.342789244-ST_YMax(g.area))/g.cell_m)::bigint AS min_y,
                           floor((20037508.342789244-ST_YMin(g.area))/g.cell_m)::bigint AS max_y
                    FROM grid g
                ),
                region AS (
                    SELECT b.*,
                           ST_Transform(ST_MakeEnvelope(
                               -20037508.342789244+min_x*cell_m,
                                20037508.342789244-(max_y+1)*cell_m,
                               -20037508.342789244+(max_x+1)*cell_m,
                                20037508.342789244-min_y*cell_m,3857),4326)::geography AS shape
                    FROM bounds b
                ),
                matching_location AS MATERIALIZED (
                    SELECT DISTINCT e.location_id
                    FROM connector c
                    JOIN evse e ON e.id=c.evse_id
                    WHERE c.is_active AND e.is_active
                      AND upper(btrim(coalesce(e.status,''))) NOT IN ('REMOVED','PLANNED')
                      AND upper(coalesce(c.standard,''))='IEC_62196_T2_COMBO'
                      AND upper(coalesce(c.power_type,''))='DC'
                )
                SELECT l.id,
                       floor((ST_X(ST_Transform(l.position::geometry,3857))
                              +20037508.342789244)/r.cell_m)::bigint AS cx,
                       floor((20037508.342789244
                              -ST_Y(ST_Transform(l.position::geometry,3857)))/r.cell_m)::bigint AS cy
                FROM matching_location m
                JOIN charging_location l ON l.id=m.location_id
                CROSS JOIN region r
                WHERE l.is_active AND l.position IS NOT NULL
                  AND ST_Intersects(l.position,r.shape)
            ),
            reference_per_location AS (
                SELECT s.id,s.cx,s.cy,
                       count(e.id) FILTER (
                           WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('REMOVED','PLANNED')
                       ) AS total_evses,
                       count(e.id) FILTER (
                           WHERE upper(btrim(coalesce(e.status,'')))='AVAILABLE'
                       ) AS free_evses,
                       count(e.id) FILTER (
                           WHERE upper(btrim(coalesce(e.status,''))) NOT IN
                             ('AVAILABLE','CHARGING','BLOCKED','RESERVED',
                              'INOPERATIVE','OUTOFORDER','PLANNED','REMOVED')
                       ) AS unclassified_evses
                FROM reference_ids s
                LEFT JOIN LATERAL (
                    SELECT e.id,e.status FROM evse e
                    WHERE e.location_id=s.id AND e.is_active OFFSET 0
                ) e ON true
                GROUP BY s.id,s.cx,s.cy
            ),
            reference_grid AS MATERIALIZED (
                SELECT cx,cy,count(*)::bigint AS location_count,
                       count(*) FILTER (
                         WHERE free_evses>0 AND unclassified_evses=0
                       )::bigint AS locations_with_free,
                       count(*) FILTER (
                         WHERE free_evses=0 AND total_evses>0 AND unclassified_evses=0
                       )::bigint AS locations_with_no_free,
                       count(*) FILTER (
                         WHERE total_evses=0 OR unclassified_evses>0
                       )::bigint AS locations_unknown
                FROM reference_per_location GROUP BY cx,cy
            ),
            id_differences AS (
                (SELECT id,cx,cy FROM original_ids
                 EXCEPT SELECT id,cx,cy FROM reference_ids)
                UNION ALL
                (SELECT id,cx,cy FROM reference_ids
                 EXCEPT SELECT id,cx,cy FROM original_ids)
            ),
            grid_differences AS (
                (SELECT * FROM original_grid EXCEPT SELECT * FROM reference_grid)
                UNION ALL
                (SELECT * FROM reference_grid EXCEPT SELECT * FROM original_grid)
            )
            SELECT (SELECT count(*) FROM original_ids) AS original_locations,
                   (SELECT count(*) FROM reference_ids) AS reference_locations,
                   (SELECT count(*) FROM id_differences) AS differing_id_cell_rows,
                   (SELECT count(*) FROM original_grid) AS original_cells,
                   (SELECT count(*) FROM reference_grid) AS reference_cells,
                   (SELECT coalesce(sum(location_count),0)::bigint FROM original_grid) AS original_grid_locations,
                   (SELECT coalesce(sum(location_count),0)::bigint FROM reference_grid) AS reference_grid_locations,
                   (SELECT count(*) FROM grid_differences) AS differing_grid_rows,
                   NOT EXISTS (SELECT 1 FROM id_differences)
                     AND NOT EXISTS (SELECT 1 FROM grid_differences)
                     AND (SELECT count(*) FROM original_ids)=(SELECT count(*) FROM reference_ids)
                     AND (SELECT count(*) FROM original_ids)=(SELECT coalesce(sum(location_count),0) FROM original_grid)
                     AND (SELECT count(*) FROM reference_ids)=(SELECT coalesce(sum(location_count),0) FROM reference_grid)
                   AS parity_matches;

            ROLLBACK;
            RESET ROLE;
            """;
        return "-- P2l national CCS2/DC connector-first independent ID and all grid status columns parity\n" +
               query.Replace("__ORIGINAL_IDS__", originalIds, StringComparison.Ordinal)
                    .Replace("__ORIGINAL_OVERVIEW__", overview, StringComparison.Ordinal);
    }
}
