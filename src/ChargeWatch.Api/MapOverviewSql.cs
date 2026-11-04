// Shared, parameterized SQL used by the staged overview endpoint and
// standalone read-only parity harness. Production MapFilter is the only
// source for filter predicates.
internal static class MapOverviewSql
{
    public static string Build(MapFilter filters, bool constrainToCoverage = false)
    {
        // The historical SQL probe uses the original five parameters; only
        // the new HTTP path supplies explicit canonical cell bounds ($6..$9).
        var filterStart = constrainToCoverage ? 10 : 6;
        var spatial = constrainToCoverage
            // Geography bounding-box overlap is a conservative spatial
            // prefilter; exact ownership is decided by canonical cx/cy.
            // Longitudes spanning >= 180 degrees bypass the polygon index
            // prefilter to avoid geographic antimeridian ambiguity.
            ? "($3 - $1 >= 180 OR l.position && ST_MakeEnvelope($1,$2,$3,$4,4326)::geography)"
            : "ST_Intersects(l.position,ST_MakeEnvelope($1,$2,$3,$4,4326)::geography)";
        var cellPredicate = constrainToCoverage
            ? "WHERE s.cx BETWEEN $6 AND $7 AND s.cy BETWEEN $8 AND $9"
            : "";
        return $"""
            WITH selected AS MATERIALIZED (
              SELECT l.id,l.country_code,l.party_id,l.source_id,
                     l.longitude,l.latitude,
                     floor((ST_X(ST_Transform(l.position::geometry,3857))
                           +20037508.342789244)/$5)::bigint AS cx,
                     floor((20037508.342789244
                           -ST_Y(ST_Transform(l.position::geometry,3857)))/$5)::bigint AS cy
              FROM charging_location l
              WHERE l.is_active AND l.position IS NOT NULL
                AND {spatial}
                {filters.LocationPredicate(filterStart)}
            ), per_location AS (
              SELECT s.id,s.country_code,s.party_id,s.source_id,
                     s.longitude,s.latitude,s.cx,s.cy,
                     count(e.id) FILTER (
                       WHERE upper(btrim(coalesce(e.status,''))) NOT IN ('REMOVED','PLANNED')
                     )::int AS total_evses,
                     -- Match the existing /map/viewport available_count rule.
                     -- The EVSE join has unique e.id rows, so DISTINCT is unnecessary.
                     count(e.id) FILTER (
                       WHERE upper(e.status)='AVAILABLE'
                     )::int AS free_evses
              FROM selected s
              LEFT JOIN evse e ON e.location_id=s.id AND e.is_active
              {cellPredicate}
              GROUP BY s.id,s.country_code,s.party_id,s.source_id,
                       s.longitude,s.latitude,s.cx,s.cy
            )
            SELECT cx,cy,count(*)::int AS location_count,
                   -- Preserve ChargeWatch Android's existing location colors:
                   -- green if free>0, else red if total>0, otherwise unknown.
                   -- An unrecognized operational EVSE status never overrides
                   -- a location's otherwise known availability.
                   count(*) FILTER (WHERE free_evses>0)::int AS locations_with_free,
                   count(*) FILTER (WHERE free_evses=0 AND total_evses>0)::int AS locations_with_no_free,
                   count(*) FILTER (WHERE total_evses=0)::int AS locations_unknown,
                   max(country_code) AS single_country_code,
                   max(party_id) AS single_party_id,
                   max(source_id) AS single_source_id,
                   max(longitude) AS single_longitude,
                   max(latitude) AS single_latitude,
                   max(total_evses) AS single_total_evses,
                   max(free_evses) AS single_free_evses
            FROM per_location
            GROUP BY cx,cy ORDER BY cy,cx
            """;
    }
}
