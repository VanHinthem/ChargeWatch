using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using System.Text.RegularExpressions;

// Phase B parity against the exact SQL linked from src/ChargeWatch.Api.
// The entire experiment is one bounded, read-only PostgreSQL statement.
internal static class OverviewPhaseBParityExporter
{
    public static string CreateCcs2Dc()
    {
        var input = new Dictionary<string,StringValues> {
            ["connectorType"] = "ccs2", ["mode"] = "dc"
        };
        if (!MapFilter.TryParse(new QueryCollection(input), out var filter, out var error))
            throw new InvalidOperationException(error);

        var old = OverviewNationalBaselineExporter.Create("ccs2_dc");
        const string explain = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS)";
        var start=old.IndexOf(explain,StringComparison.Ordinal);
        var stop=old.IndexOf("ROLLBACK;",start,StringComparison.Ordinal);
        if (start < 0 || stop < 0) throw new InvalidOperationException("Original overview not found.");
        var original=old[(start+explain.Length)..stop].Trim().TrimEnd(';');

        var phaseB=MapOverviewSql.Build(filter);
        // Parameter translation reproduces the phase-B endpoint's five
        // actual bound arguments for the canonical grid8 Netherlands viewport.
        // $6+ are production MapFilter arrays, not manually rewritten filters.
        var values=new Dictionary<int,string> {
            [1]="(SELECT ST_XMin(shape::geometry) FROM phaseb_region)",
            [2]="(SELECT ST_YMin(shape::geometry) FROM phaseb_region)",
            [3]="(SELECT ST_XMax(shape::geometry) FROM phaseb_region)",
            [4]="(SELECT ST_YMax(shape::geometry) FROM phaseb_region)",
            [5]="(40075016.68557849::double precision / 256.0)",
            [6]="ARRAY['ccs2']::text[]",
            [7]="ARRAY['dc']::text[]"
        };
        phaseB=Regex.Replace(phaseB,@"\$(\d+)",m => {
            var number=int.Parse(m.Groups[1].Value);
            return values.TryGetValue(number,out var v)
                ? v : throw new InvalidOperationException("Unexpected parameter "+number);
        });
        if (phaseB.Contains("$1",StringComparison.Ordinal))
            throw new InvalidOperationException("An untranslated parameter remains.");

        const string selectedSplit = "), per_location AS (";
        var selectedStop=phaseB.IndexOf(selectedSplit,StringComparison.Ordinal);
        var originalStop=original.IndexOf(selectedSplit,StringComparison.Ordinal);
        if (selectedStop < 0 || originalStop < 0 ||
            !phaseB.StartsWith("WITH selected AS MATERIALIZED (",StringComparison.Ordinal) ||
            !original.StartsWith("WITH grid AS (",StringComparison.Ordinal))
            throw new InvalidOperationException("Unexpected CTE layout.");
        var phaseBIds=phaseB[..selectedStop]+") SELECT id,cx,cy FROM selected";
        var originalIds=original[..originalStop]+") SELECT id,cx,cy FROM selected";

        return """
            -- P2m phase-B exact SQL parity against previously validated P2l national CCS2/DC overview
            SET ROLE chargewatch_read;
            BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
            SET LOCAL statement_timeout = '5s';
            SET LOCAL lock_timeout = '1s';
            SET LOCAL jit = off;

            WITH phaseb_region AS MATERIALIZED (
                WITH grid AS (
                  SELECT 40075016.68557849::double precision / 256.0 AS cell_m,
                         ST_Transform(ST_MakeEnvelope(3.00,50.60,7.40,53.80,4326),3857) AS area
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
            original_ids AS MATERIALIZED (
                __ORIGINAL_IDS__
            ),
            phaseb_ids AS MATERIALIZED (
                __PHASEB_IDS__
            ),
            original_grid AS MATERIALIZED (
                __ORIGINAL_GRID__
            ),
            phaseb_grid AS MATERIALIZED (
                __PHASEB_GRID__
            ),
            id_diff AS (
                (SELECT id,cx,cy FROM original_ids
                 EXCEPT SELECT id,cx,cy FROM phaseb_ids)
                UNION ALL
                (SELECT id,cx,cy FROM phaseb_ids
                 EXCEPT SELECT id,cx,cy FROM original_ids)
            ),
            grid_diff AS (
                (SELECT cx,cy,location_count,locations_with_free,
                        locations_with_no_free,locations_unknown FROM original_grid
                 EXCEPT
                 SELECT cx,cy,location_count,locations_with_free,
                        locations_with_no_free,locations_unknown FROM phaseb_grid)
                UNION ALL
                (SELECT cx,cy,location_count,locations_with_free,
                        locations_with_no_free,locations_unknown FROM phaseb_grid
                 EXCEPT
                 SELECT cx,cy,location_count,locations_with_free,
                        locations_with_no_free,locations_unknown FROM original_grid)
            )
            SELECT (SELECT count(*) FROM original_ids) AS original_locations,
                   (SELECT count(*) FROM phaseb_ids) AS phaseb_locations,
                   (SELECT count(*) FROM id_diff) AS differing_id_cells,
                   (SELECT count(*) FROM original_grid) AS original_cells,
                   (SELECT count(*) FROM phaseb_grid) AS phaseb_cells,
                   (SELECT coalesce(sum(location_count),0)::bigint FROM phaseb_grid)
                       AS phaseb_grid_locations,
                   (SELECT count(*) FROM grid_diff) AS differing_full_grid_rows,
                   (SELECT count(*) FROM phaseb_grid
                       WHERE location_count <> locations_with_free +
                           locations_with_no_free + locations_unknown)
                       AS invalid_status_partitions,
                   (SELECT count(*) FROM phaseb_grid
                       WHERE location_count=1) AS single_location_cells,
                   (SELECT count(*) FROM phaseb_grid
                       WHERE location_count>1) AS cluster_cells,
                   NOT EXISTS (SELECT 1 FROM id_diff)
                   AND NOT EXISTS (SELECT 1 FROM grid_diff)
                   AND (SELECT count(*) FROM phaseb_ids) =
                       (SELECT coalesce(sum(location_count),0) FROM phaseb_grid)
                   AND NOT EXISTS (SELECT 1 FROM phaseb_grid
                       WHERE location_count <> locations_with_free +
                           locations_with_no_free + locations_unknown)
                   AS parity_matches;

            ROLLBACK;
            RESET ROLE;
            """
            .Replace("__ORIGINAL_IDS__",originalIds,StringComparison.Ordinal)
            .Replace("__PHASEB_IDS__",phaseBIds,StringComparison.Ordinal)
            .Replace("__ORIGINAL_GRID__",original,StringComparison.Ordinal)
            .Replace("__PHASEB_GRID__",phaseB.Trim().TrimEnd(';'),StringComparison.Ordinal);
    }
}
