// P2j: independently count geographical location IDs and fixed-grid
// ownership, then compare per-cell locationCount against P2i overview.
// No production code, no database connection; all SQL is read-only.
internal static class OverviewNationalCensusExporter
{
    public static string Create()
    {
        var full = OverviewNationalBaselineExporter.Create("unfiltered");
        const string planPrefix = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS)";
        const string footer = "ROLLBACK;";
        var begin = full.IndexOf(planPrefix, StringComparison.Ordinal);
        var end = full.IndexOf(footer, begin, StringComparison.Ordinal);
        if (begin < 0 || end < 0)
            throw new InvalidOperationException("Unexpected original overview format.");
        var overviewSql = full[(begin + planPrefix.Length)..end].Trim().TrimEnd(';');
        if (!overviewSql.StartsWith("WITH grid AS", StringComparison.Ordinal) ||
            !overviewSql.Contains("ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326)", StringComparison.Ordinal) ||
            !overviewSql.Contains("40075016.68557849::double precision / 256.0", StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected national overview grid.");

        var sql = """
            SET ROLE chargewatch_read;
            BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
            SET LOCAL statement_timeout = '5s';
            SET LOCAL lock_timeout = '1s';
            SET LOCAL jit = off;

            WITH overview AS MATERIALIZED (
                __OVERVIEW__
            ),
            geo AS MATERIALIZED (
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
                )
                SELECT l.id,
                       floor((ST_X(ST_Transform(l.position::geometry,3857))
                           +20037508.342789244)/r.cell_m)::bigint AS cx,
                       floor((20037508.342789244
                           -ST_Y(ST_Transform(l.position::geometry,3857)))/r.cell_m)::bigint AS cy,
                       r.min_x,r.max_x,r.min_y,r.max_y
                FROM charging_location l CROSS JOIN region r
                WHERE l.is_active AND l.position IS NOT NULL
                  AND ST_Intersects(l.position,r.shape)
            ),
            counted AS (
                SELECT cx,cy,count(*)::bigint AS location_count,
                       count(DISTINCT id)::bigint AS distinct_ids
                FROM geo GROUP BY cx,cy
            ),
            differences AS (
                (SELECT cx,cy,location_count FROM overview
                 EXCEPT SELECT cx,cy,location_count FROM counted)
                UNION ALL
                (SELECT cx,cy,location_count FROM counted
                 EXCEPT SELECT cx,cy,location_count FROM overview)
            )
            SELECT (SELECT count(*) FROM geo) AS geography_locations,
                   (SELECT count(DISTINCT id) FROM geo) AS geography_distinct_ids,
                   (SELECT coalesce(sum(location_count),0)::bigint FROM counted) AS census_locations,
                   (SELECT coalesce(sum(location_count),0)::bigint FROM overview) AS overview_locations,
                   (SELECT count(*) FROM counted) AS census_nonempty_cells,
                   (SELECT count(*) FROM overview) AS overview_nonempty_cells,
                   (SELECT count(*) FROM differences) AS differing_cell_counts,
                   (SELECT count(*) FROM geo
                       WHERE cx IS NULL OR cy IS NULL OR cx<min_x OR cx>max_x
                          OR cy<min_y OR cy>max_y) AS invalid_cell_assignments,
                   NOT EXISTS (SELECT 1 FROM differences)
                   AND (SELECT count(*) FROM geo)=(SELECT count(DISTINCT id) FROM geo)
                   AND (SELECT count(*) FROM geo)=(SELECT coalesce(sum(location_count),0) FROM overview)
                   AND NOT EXISTS (SELECT 1 FROM geo
                       WHERE cx IS NULL OR cy IS NULL OR cx<min_x OR cx>max_x
                          OR cy<min_y OR cy>max_y) AS population_matches;

            ROLLBACK;
            RESET ROLE;
            """;
        return "-- P2j independent grid8 geographical census against production MapFilter unfiltered overview\n" +
            sql.Replace("__OVERVIEW__", overviewSql, StringComparison.Ordinal);
    }
}
