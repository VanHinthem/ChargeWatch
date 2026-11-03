using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using Npgsql;
using NpgsqlTypes;

// A bounded, read-only experiment for #113: materialize geography before
// applying exactly the same linked production MapFilter predicate.
internal static class OverviewGeoFirstExporter
{
    public static string Create()
    {
        var cases = new (string Name, Dictionary<string, StringValues> Query)[]
        {
            ("ccs2_dc", new() { ["connectorType"] = "ccs2", ["mode"] = "dc" }),
            ("ccs2_dc_minfree1", new()
            {
                ["connectorType"] = "ccs2", ["mode"] = "dc", ["minFree"] = "1"
            })
        };
        return string.Join("\n\n", cases.Select(c => Emit(c.Name, c.Query)));
    }

    public static string CreateScenario(string name, bool includeParity = true) => name switch
    {
        "ccs2_dc" => Emit(name, new() { ["connectorType"] = "ccs2", ["mode"] = "dc" }, includeParity),
        "ccs2_dc_minfree1" => Emit(name, new() { ["connectorType"] = "ccs2", ["mode"] = "dc", ["minFree"] = "1" }, includeParity),
        _ => throw new ArgumentOutOfRangeException(nameof(name), name, "Unknown fixed benchmark scenario")
    };

    // P2d: force a correlated location-by-location filter evaluation without
    // changing the generated MapFilter expression. OFFSET 0 acts as a
    // subquery-planner fence; actual behavior must be checked with EXPLAIN.
    public static string CreateCorrelatedCcs2Dc() =>
        Emit("ccs2_dc", new() { ["connectorType"] = "ccs2", ["mode"] = "dc" },
             includeParity: true, correlateTechnicalFilter: true);

    // P2e: keep P2d's proven filter selection but restrict the final EVSE
    // availability join to selected locations through a fenced LATERAL lookup.
    public static string CreateCorrelatedLateralAvailabilityCcs2Dc() =>
        Emit("ccs2_dc", new() { ["connectorType"] = "ccs2", ["mode"] = "dc" },
             includeParity: true, correlateTechnicalFilter: true,
             lateralAvailability: true);

    // P2f: an intentionally bounded, single-EXPLAIN medium-area probe.
    // Avoid running the expensive two-query raster-parity check before the
    // first scale measurement. Region is fixed; national test not emitted.
    public static string CreateMediumRegionCcs2Dc() =>
        Emit("ccs2_dc", new() { ["connectorType"] = "ccs2", ["mode"] = "dc" },
             includeParity: false, correlateTechnicalFilter: true,
             lateralAvailability: true, includeAggregateParity: false,
             mediumRegion: true);

    private static string Emit(string name, Dictionary<string, StringValues> entries,
        bool includeParity = true, bool correlateTechnicalFilter = false,
        bool lateralAvailability = false, bool includeAggregateParity = true,
        bool mediumRegion = false)
    {
        if (!MapFilter.TryParse(new QueryCollection(entries), out var filter, out var error))
            throw new InvalidOperationException(error);
        using var command = new NpgsqlCommand();
        for (var i = 0; i < 5; i++)
            command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Double, Value = 0d });
        filter.AddParameters(command);
        var predicate = Regex.Replace(filter.LocationPredicate(6), @"\$(\d+)", match =>
        {
            var index = int.Parse(match.Groups[1].Value) - 1;
            if (index < 5 || index >= command.Parameters.Count)
                throw new InvalidOperationException("Unexpected parameter.");
            var parameter = command.Parameters[index];
            if (parameter.NpgsqlDbType != (NpgsqlDbType.Array | NpgsqlDbType.Text))
                throw new InvalidOperationException("Expected validated text[] filter.");
            return "ARRAY[" + string.Join(",", ((string[])parameter.Value!)
                .Select(value => "'" + value.Replace("'", "''") + "'")) + "]::text[]";
        });
        var geo = """
        grid AS (
            SELECT 40075016.68557849::double precision/2048.0 AS cell_m,
                   ST_Transform(ST_MakeEnvelope(5.40,51.70,5.62,51.83,4326),3857) AS area
        ), bounds AS (
            SELECT g.cell_m,
                   floor((ST_XMin(g.area)+20037508.342789244)/g.cell_m)::bigint AS min_x,
                   floor((ST_XMax(g.area)+20037508.342789244)/g.cell_m)::bigint AS max_x,
                   floor((20037508.342789244-ST_YMax(g.area))/g.cell_m)::bigint AS min_y,
                   floor((20037508.342789244-ST_YMin(g.area))/g.cell_m)::bigint AS max_y
            FROM grid g
        ), region AS (
            SELECT b.*, ST_Transform(ST_MakeEnvelope(
                       -20037508.342789244+min_x*cell_m,
                        20037508.342789244-(max_y+1)*cell_m,
                       -20037508.342789244+(max_x+1)*cell_m,
                        20037508.342789244-min_y*cell_m,3857),4326)::geography AS shape
            FROM bounds b
        ),
        geo_locations AS MATERIALIZED (
            SELECT l.id,l.operator_name,
                   floor((ST_X(ST_Transform(l.position::geometry,3857))
                         +20037508.342789244)/r.cell_m)::bigint AS cx,
                   floor((20037508.342789244
                         -ST_Y(ST_Transform(l.position::geometry,3857)))/r.cell_m)::bigint AS cy
            FROM charging_location l CROSS JOIN region r
            WHERE l.is_active AND l.position IS NOT NULL
              AND ST_Intersects(l.position,r.shape)
        ),
        selected AS MATERIALIZED (
            SELECT l.id,l.cx,l.cy FROM geo_locations l
            WHERE true __FILTER__
        )
        """;
        if (mediumRegion)
        {
            // Regional zoom-10 Web Mercator area: minLon=5.2, minLat=51.6,
            // maxLon=5.9, maxLat=52.1. Exactly one occurrence per fragment.
            const string oldEnvelope = "ST_MakeEnvelope(5.40,51.70,5.62,51.83,4326)";
            const string newEnvelope = "ST_MakeEnvelope(5.20,51.60,5.90,52.10,4326)";
            const string oldSize = "40075016.68557849::double precision/2048.0";
            const string newSize = "40075016.68557849::double precision/1024.0";
            if (geo.Split(oldEnvelope, StringSplitOptions.None).Length != 2 ||
                geo.Split(oldSize, StringSplitOptions.None).Length != 2)
                throw new InvalidOperationException("P2f region template mismatched.");
            geo = geo.Replace(oldEnvelope, newEnvelope, StringComparison.Ordinal)
                     .Replace(oldSize, newSize, StringComparison.Ordinal);
        }

        // The parity statement has one DB snapshot and two independent
        // selections; only placement of the same MapFilter predicate differs.
        var parity = """
        WITH __GEO__,
        original AS MATERIALIZED (
            SELECT l.id FROM charging_location l CROSS JOIN region r
            WHERE l.is_active AND l.position IS NOT NULL
              AND ST_Intersects(l.position,r.shape)
              __FILTER__
        ),
        diff AS (
            (SELECT id FROM original EXCEPT SELECT id FROM selected)
            UNION ALL
            (SELECT id FROM selected EXCEPT SELECT id FROM original)
        )
        SELECT '__CASE__'::text AS fixture,
               (SELECT count(*) FROM original) AS original_locations,
               (SELECT count(*) FROM selected) AS geofirst_locations,
               (SELECT count(*) FROM diff) AS differing_ids,
               NOT EXISTS (SELECT 1 FROM diff) AS ids_match;
        """;
        var perf = """
        EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
        WITH __GEO__,
        per_location AS (
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
            FROM selected s
            LEFT JOIN evse e ON e.location_id=s.id AND e.is_active
            GROUP BY s.id,s.cx,s.cy
        )
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
        FROM per_location GROUP BY cx,cy ORDER BY cy,cx;
        """;
        // Replace only the filter clause, not a whitespace-sensitive multi-line
        // fragment: C# raw-string indentation is normalized at runtime.
        const string filterMarker = "WHERE true __FILTER__";
        if (correlateTechnicalFilter &&
            geo.Split(filterMarker, StringSplitOptions.None).Length != 2)
            throw new InvalidOperationException("Expected exactly one filter marker.");
        var geoTemplate = correlateTechnicalFilter
            ? geo.Replace(filterMarker,
                "CROSS JOIN LATERAL (SELECT 1 AS accepted WHERE true __FILTER__ OFFSET 0) AS matched",
                StringComparison.Ordinal)
            : geo;
        if (correlateTechnicalFilter &&
            !geoTemplate.Contains("CROSS JOIN LATERAL (SELECT 1 AS accepted WHERE true __FILTER__ OFFSET 0) AS matched", StringComparison.Ordinal))
            throw new InvalidOperationException("Correlated filter SQL template not applied.");

        string Render(string template) => template
            .Replace("__GEO__", geoTemplate, StringComparison.Ordinal)
            .Replace("__FILTER__", predicate, StringComparison.Ordinal)
            .Replace("__CASE__", name, StringComparison.Ordinal);
        var renderedPerf = Render(perf);
        if (lateralAvailability)
        {
            if (!correlateTechnicalFilter)
                throw new InvalidOperationException("P2e requires the P2d correlated filter.");
            const string originalJoin = "LEFT JOIN evse e ON e.location_id=s.id AND e.is_active";
            const string indexedJoin = """
                LEFT JOIN LATERAL (
                    SELECT e.id,e.status FROM evse e
                    WHERE e.location_id=s.id AND e.is_active
                    OFFSET 0
                ) e ON true
                """;
            if (renderedPerf.Split(originalJoin, StringSplitOptions.None).Length != 2)
                throw new InvalidOperationException("Expected exactly one availability join.");
            var indexedPerf = renderedPerf.Replace(originalJoin, indexedJoin, StringComparison.Ordinal);
            const string explainPrefix = "EXPLAIN (ANALYZE, BUFFERS, SETTINGS)";
            if (!renderedPerf.TrimStart().StartsWith(explainPrefix, StringComparison.Ordinal))
                throw new InvalidOperationException("Unexpected EXPLAIN prefix.");

            static string AsQuery(string statement) => statement
                .Trim().Replace("EXPLAIN (ANALYZE, BUFFERS, SETTINGS)", "", StringComparison.Ordinal)
                .Trim().TrimEnd(';');
            // Both complete per-cell result sets are evaluated inside one SQL
            // statement/snapshot. EXCEPT in both directions compares counts AND
            // all three availability partitions, not just selected ID sets.
            var aggregateParity = """
                WITH baseline AS MATERIALIZED (
                __BASELINE__
                ), indexed AS MATERIALIZED (
                __INDEXED__
                ), differences AS (
                    (SELECT * FROM baseline EXCEPT SELECT * FROM indexed)
                    UNION ALL
                    (SELECT * FROM indexed EXCEPT SELECT * FROM baseline)
                )
                SELECT (SELECT count(*) FROM baseline) AS baseline_cells,
                       (SELECT count(*) FROM indexed) AS indexed_cells,
                       (SELECT coalesce(sum(location_count),0)::bigint FROM baseline) AS baseline_locations,
                       (SELECT coalesce(sum(location_count),0)::bigint FROM indexed) AS indexed_locations,
                       (SELECT count(*) FROM differences) AS differing_cell_rows,
                       NOT EXISTS (SELECT 1 FROM differences) AS aggregates_match;
                """
                .Replace("__BASELINE__", AsQuery(renderedPerf), StringComparison.Ordinal)
                .Replace("__INDEXED__", AsQuery(indexedPerf), StringComparison.Ordinal);
            return "-- P2e selected-location LATERAL availability with original MapFilter: " + name + "\n" + """
                SET ROLE chargewatch_read;
                BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
                SET LOCAL statement_timeout = '5s';
                SET LOCAL lock_timeout = '1s';
                SET LOCAL jit = off;
                """ + "\n" + (includeParity ? Render(parity) + "\n" : "") +
                (includeAggregateParity ? aggregateParity + "\n" : "") + indexedPerf + "\n" + """
                ROLLBACK;
                RESET ROLE;
                """;
        }
        return "-- " + (correlateTechnicalFilter ? "P2d correlated location-first" : "P2b geography-first") + " with original MapFilter: " + name + "\n" + """
        SET ROLE chargewatch_read;
        BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
        SET LOCAL statement_timeout = '5s';
        SET LOCAL lock_timeout = '1s';
        SET LOCAL jit = off;
        """ + "\n" + (includeParity ? Render(parity) + "\n" : "") + renderedPerf + "\n" + """
        ROLLBACK;
        RESET ROLE;
        """;
    }
}
