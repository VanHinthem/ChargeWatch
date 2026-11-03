using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using Npgsql;
using NpgsqlTypes;

internal static class Q4cSqlExporter
{
    public static string CreateCcs2Dc() => Create("Q4c-01 CCS2/DC", new Dictionary<string, StringValues>
    {
        ["connectorType"] = "ccs2",
        ["mode"] = "dc"
    });

    public static string CreatePowerBands() => string.Join("\n\n",
        new[] { "lt50", "gte50", "gte150", "gte300" }
            .Select(power => Create($"Q4c-02 {power}", new Dictionary<string, StringValues>
            {
                ["power"] = power
            })));

    public static string CreateMinFree() => string.Join("\n\n",
        new[] { "1", "2" }
            .Select(value => Create($"Q4c-03 minFree={value}", new Dictionary<string, StringValues>
            {
                ["minFree"] = value
            })));

    public static string CreateCombinedMinFree() => Create("Q4c-04 CCS2 DC gte150 minFree=2",
        new Dictionary<string, StringValues>
        {
            ["connectorType"] = "ccs2",
            ["mode"] = "dc",
            ["power"] = "gte150",
            ["minFree"] = "2"
        });

    public static string CreateOperators() => string.Join("\n\n", new[]
    {
        Create("Q4c-05 Vattenfall", new Dictionary<string, StringValues>
        {
            ["operator"] = "Vattenfall InCharge"
        }),
        Create("Q4c-05 Vattenfall+50five", new Dictionary<string, StringValues>
        {
            ["operator"] = new StringValues(new[] { "Vattenfall InCharge", "50five" })
        }),
        Create("Q4c-05 duplicate Vattenfall", new Dictionary<string, StringValues>
        {
            ["operator"] = new StringValues(new[] { "Vattenfall InCharge", "Vattenfall InCharge" })
        })
    });

    public static string CreateMultiConnectorMode() => string.Join("\n\n", new[]
    {
        Create("Q4c-06 CCS2+CHAdeMO AC+DC", new Dictionary<string, StringValues>
        {
            ["connectorType"] = new StringValues(new[] { "ccs2", "chademo" }),
            ["mode"] = new StringValues(new[] { "ac", "dc" })
        }),
        Create("Q4c-06 TYPE2+CCS2 AC+DC", new Dictionary<string, StringValues>
        {
            ["connectorType"] = new StringValues(new[] { "type2", "ccs2" }),
            ["mode"] = new StringValues(new[] { "ac", "dc" })
        }),
        Create("Q4c-06 CCS2+CHAdeMO DC", new Dictionary<string, StringValues>
        {
            ["connectorType"] = new StringValues(new[] { "ccs2", "chademo" }),
            ["mode"] = "dc"
        })
    });

    public static string CreateNoMatch() => Create("Q4c-07 nonexistent operator",
        new Dictionary<string, StringValues>
        {
            ["operator"] = "__Q4_NONEXISTENT_OPERATOR_113__"
        });

    public static string CreateRingScope()
    {
        var query = new QueryCollection(new Dictionary<string, StringValues>
        {
            ["connectorType"] = "ccs2",
            ["mode"] = "dc"
        });
        if (!MapFilter.TryParse(query, out var filter, out var error))
            throw new InvalidOperationException(error);
        using var command = new NpgsqlCommand();
        for (var i = 0; i < 4; i++)
            command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Double, Value = 0d });
        filter.AddParameters(command);
        var predicate = ExpandFixedArrayParameters(filter.LocationPredicate(5), command, 4);
        return """
        -- Q4c-08: selected CCS2/DC locations; provisional location-wide ring audit.
        SET ROLE chargewatch_read;
        BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
        SET LOCAL statement_timeout = '5s';
        SET LOCAL lock_timeout = '1s';
        SET LOCAL jit = off;

        WITH selected AS MATERIALIZED (
          SELECT l.id,l.country_code,l.party_id,l.source_id
          FROM charging_location l
          WHERE l.is_active AND l.position IS NOT NULL
            AND ST_Intersects(l.position,
                ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography)
            __Q4C08_LOCATION_PREDICATE__
        ),
        all_counts AS MATERIALIZED (
          SELECT s.id,
                 count(e.id) FILTER (
                   WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')
                 )::bigint AS all_operational,
                 count(e.id) FILTER (
                   WHERE upper(btrim(coalesce(e.status,''))) = 'AVAILABLE'
                 )::bigint AS all_free,
                 count(e.id) FILTER (
                   WHERE upper(btrim(coalesce(e.status,''))) NOT IN
                     ('AVAILABLE','CHARGING','BLOCKED','RESERVED','INOPERATIVE',
                      'OUTOFORDER','PLANNED','REMOVED')
                 )::bigint AS all_unclassified
          FROM selected s
          LEFT JOIN evse e ON e.location_id=s.id AND e.is_active
          GROUP BY s.id
        ),
        filtered_evse AS MATERIALIZED (
          SELECT s.id AS location_id, e.id AS evse_id
          FROM selected s
          JOIN evse e ON e.location_id=s.id AND e.is_active
          WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')
            AND EXISTS (
              SELECT 1 FROM connector c
              WHERE c.evse_id=e.id AND c.is_active
                AND upper(coalesce(c.standard,''))='IEC_62196_T2_COMBO'
                AND upper(coalesce(c.power_type,''))='DC'
            )
        ),
        matching_counts AS (
          SELECT location_id,count(*)::bigint AS matching_operational
          FROM filtered_evse GROUP BY location_id
        ),
        per_location AS MATERIALIZED (
          SELECT s.id,
                 concat_ws('-',s.country_code,s.party_id,s.source_id) AS feature_id,
                 a.all_operational,a.all_free,a.all_unclassified,
                 coalesce(m.matching_operational,0)::bigint AS matching_operational,
                 CASE
                   WHEN a.all_operational=0 OR a.all_unclassified>0 THEN 'unknown'
                   WHEN a.all_free>0 THEN 'green'
                   ELSE 'red'
                 END AS provisional_ring_status
          FROM selected s
          JOIN all_counts a USING(id)
          LEFT JOIN matching_counts m ON m.location_id=s.id
        )
        SELECT id,feature_id,matching_operational,all_operational,
               all_free,all_unclassified,provisional_ring_status,
               all_operational >= matching_operational AS scope_ok,
               all_operational > matching_operational AS has_other_evses
        FROM per_location ORDER BY id;

        -- Summary: one row per selected location; ring partitions all selected locations.
        WITH selected AS MATERIALIZED (
          SELECT l.id
          FROM charging_location l
          WHERE l.is_active AND l.position IS NOT NULL
            AND ST_Intersects(l.position,
                ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography)
            __Q4C08_LOCATION_PREDICATE__
        ),
        counts AS (
          SELECT s.id,
                 count(e.id) FILTER (
                   WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('PLANNED','REMOVED')
                 ) AS total,
                 count(e.id) FILTER (
                   WHERE upper(btrim(coalesce(e.status,'')))='AVAILABLE'
                 ) AS free,
                 count(e.id) FILTER (
                   WHERE upper(btrim(coalesce(e.status,''))) NOT IN
                     ('AVAILABLE','CHARGING','BLOCKED','RESERVED','INOPERATIVE',
                      'OUTOFORDER','PLANNED','REMOVED')
                 ) AS unclassified
          FROM selected s LEFT JOIN evse e ON e.location_id=s.id AND e.is_active
          GROUP BY s.id
        ),
        categories AS (
          SELECT CASE WHEN total=0 OR unclassified>0 THEN 'unknown'
                      WHEN free>0 THEN 'green' ELSE 'red' END category
          FROM counts
        )
        SELECT count(*)::bigint AS selected_locations,
               count(*) FILTER (WHERE category='green')::bigint AS green_locations,
               count(*) FILTER (WHERE category='red')::bigint AS red_locations,
               count(*) FILTER (WHERE category='unknown')::bigint AS unknown_locations,
               count(*) = count(*) FILTER (WHERE category IN ('green','red','unknown')) AS partition_ok
        FROM categories;

        ROLLBACK;
        RESET ROLE;
        """.Replace("__Q4C08_LOCATION_PREDICATE__", predicate, StringComparison.Ordinal);
    }

    private static string Create(string label, Dictionary<string, StringValues> entries)
    {
        // Known, fixed fixture: no arbitrary user input is converted into SQL.
        var query = new QueryCollection(entries);
        if (!MapFilter.TryParse(query, out var filter, out var error))
            throw new InvalidOperationException(error);

        // Use actual production filter generation and array binding.
        using var viewport = new NpgsqlCommand();
        using var overview = new NpgsqlCommand();
        for (var i = 0; i < 4; i++) viewport.Parameters.Add(new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Double, Value = 0d });
        for (var i = 0; i < 5; i++) overview.Parameters.Add(new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Double, Value = 0d });
        filter.AddParameters(viewport);
        filter.AddParameters(overview);
        var viewportPredicate = ExpandFixedArrayParameters(filter.LocationPredicate(5), viewport, 4);
        var overviewPredicate = ExpandFixedArrayParameters(filter.LocationPredicate(6), overview, 5);
        if (viewportPredicate != overviewPredicate)
            throw new InvalidOperationException("Expanded filter predicates differ.");

        // SQL mirrors the tested Q4c-00 raster-ownership probe; only the
        // predicates above are sourced from the shared production generator.
        return $$"""
        -- {{label}} generated by MapFilterQ4Probe using the actual MapFilter.cs.
        -- Fixed validated filter values are rendered as PostgreSQL literals
        -- for manual read-only psql execution; this is NOT production SQL binding.
        SET ROLE chargewatch_read;
        BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
        SET LOCAL statement_timeout = '5s';
        SET LOCAL lock_timeout = '1s';
        SET LOCAL jit = off;

        WITH p AS (
          SELECT 11::integer AS grid_level,
                 40075016.68557849::float8 / 2048.0 AS cell_m,
                 ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography AS viewport_area,
                 ST_Transform(ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326),3857) AS mercator_bbox
        ),
        bounds AS (
          SELECT p.*,
                 floor((ST_XMin(mercator_bbox)+20037508.342789244)/cell_m)::bigint AS first_x,
                 floor((ST_XMax(mercator_bbox)+20037508.342789244)/cell_m)::bigint AS last_x,
                 floor((20037508.342789244-ST_YMax(mercator_bbox))/cell_m)::bigint AS first_y,
                 floor((20037508.342789244-ST_YMin(mercator_bbox))/cell_m)::bigint AS last_y
          FROM p
        ),
        region AS (
          SELECT b.*,
                 ST_Transform(ST_MakeEnvelope(
                   -20037508.342789244+first_x*cell_m,
                    20037508.342789244-(last_y+1)*cell_m,
                   -20037508.342789244+(last_x+1)*cell_m,
                    20037508.342789244-first_y*cell_m,
                   3857),4326)::geography AS search_area
          FROM bounds b
        ),
        viewport_ids AS MATERIALIZED (
          SELECT l.id
          FROM charging_location l CROSS JOIN p
          WHERE l.is_active AND l.position IS NOT NULL
            AND ST_Intersects(l.position,p.viewport_area)
            {{viewportPredicate}}
        ),
        grid_candidates AS MATERIALIZED (
          SELECT l.id,l.position,
                 floor((ST_X(ST_Transform(l.position::geometry,3857))
                        +20037508.342789244)/r.cell_m)::bigint AS cell_x,
                 floor((20037508.342789244
                       -ST_Y(ST_Transform(l.position::geometry,3857)))
                       /r.cell_m)::bigint AS cell_y
          FROM charging_location l CROSS JOIN region r
          WHERE l.is_active AND l.position IS NOT NULL
            AND ST_Intersects(l.position,r.search_area)
            {{overviewPredicate}}
        ),
        grid_owned AS MATERIALIZED (
          SELECT g.id,g.position FROM grid_candidates g CROSS JOIN region r
          WHERE g.cell_x BETWEEN r.first_x AND r.last_x
            AND g.cell_y BETWEEN r.first_y AND r.last_y
        ),
        overview_clipped AS MATERIALIZED (
          SELECT g.id FROM grid_owned g CROSS JOIN p
          WHERE ST_Intersects(g.position,p.viewport_area)
        ),
        diff AS (
          (SELECT id FROM viewport_ids EXCEPT SELECT id FROM overview_clipped)
          UNION ALL
          (SELECT id FROM overview_clipped EXCEPT SELECT id FROM viewport_ids)
        )
        SELECT
          '{{label}}'::text AS fixture,
          (SELECT count(*) FROM viewport_ids) AS viewport_ids,
          (SELECT count(*) FROM grid_candidates) AS grid_candidates,
          (SELECT count(*) FROM grid_owned) AS grid_owned,
          (SELECT count(*) FROM overview_clipped) AS overview_clipped_ids,
          (SELECT count(*) FROM diff) AS differing_ids,
          (SELECT count(*) FROM viewport_ids) =
             (SELECT count(DISTINCT id) FROM viewport_ids) AS viewport_unique,
          (SELECT count(*) FROM overview_clipped) =
             (SELECT count(DISTINCT id) FROM overview_clipped) AS overview_unique,
          NOT EXISTS(SELECT 1 FROM diff) AS ids_match;

        ROLLBACK;
        RESET ROLE;
        """;
    }

    private static string ExpandFixedArrayParameters(string predicate, NpgsqlCommand cmd, int prefixCount)
    {
        return Regex.Replace(predicate, @"\$(\d+)", match =>
        {
            var index = int.Parse(match.Groups[1].Value) - 1;
            if (index < prefixCount || index >= cmd.Parameters.Count)
                throw new InvalidOperationException("Invalid generated filter parameter index.");
            var parameter = cmd.Parameters[index];
            if (parameter.NpgsqlDbType != (NpgsqlDbType.Array | NpgsqlDbType.Text))
                throw new InvalidOperationException("Only validated text[] filter parameters are supported.");
            var values = (string[])parameter.Value!;
            return "ARRAY[" + string.Join(",", values.Select(v => "'" + v.Replace("'", "''") + "'")) + "]::text[]";
        });
    }
}
