internal static class Q4cEvseAuditExporter
{
    public static string Create()
    {
        // Only the power arithmetic is shared with production. The independent
        // JOIN + GROUP BY audit deliberately does NOT call LocationPredicate.
        var watts = MapFilter.EffectiveWattsSql;
        return $$"""
        -- Q4c-04b: independently count unique matching AVAILABLE EVSEs.
        -- Generated from the real production EffectiveWattsSql arithmetic.
        -- Fixed CCS2/DC >=150kW fixture. Read-only, scoped to Oss test bbox.
        SET ROLE chargewatch_read;
        BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
        SET LOCAL statement_timeout = '5s';
        SET LOCAL lock_timeout = '1s';
        SET LOCAL jit = off;

        WITH candidate_locations AS MATERIALIZED (
          SELECT l.id, l.country_code, l.party_id, l.source_id
          FROM charging_location l
          WHERE l.is_active AND l.position IS NOT NULL
            AND ST_Intersects(l.position,
                 ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography)
        ),
        matching_connector_rows AS MATERIALIZED (
          SELECT l.id AS location_id, e.id AS evse_id, c.id AS connector_id
          FROM candidate_locations l
          JOIN evse e ON e.location_id = l.id
          JOIN connector c ON c.evse_id = e.id
          WHERE e.is_active AND c.is_active
            AND upper(btrim(coalesce(e.status,''))) = 'AVAILABLE'
            AND upper(coalesce(c.standard,'')) = 'IEC_62196_T2_COMBO'
            AND upper(coalesce(c.power_type,'')) = 'DC'
            AND ({{watts}}) >= 150000
        ),
        per_evse AS MATERIALIZED (
          SELECT location_id, evse_id, count(*)::bigint AS qualifying_connectors
          FROM matching_connector_rows
          GROUP BY location_id, evse_id
        ),
        per_location AS (
          SELECT l.id, l.country_code || '-' || l.party_id || '-' || l.source_id AS feature_id,
                 coalesce(sum(p.qualifying_connectors),0)::bigint AS qualifying_connector_rows,
                 count(p.evse_id)::bigint AS distinct_qualifying_evses,
                 count(p.evse_id) FILTER (WHERE p.qualifying_connectors > 1)::bigint AS evses_with_multiple_qualifying_connectors
          FROM candidate_locations l
          LEFT JOIN per_evse p ON p.location_id = l.id
          GROUP BY l.id,l.country_code,l.party_id,l.source_id
        )
        SELECT id, feature_id,
               qualifying_connector_rows,
               distinct_qualifying_evses,
               evses_with_multiple_qualifying_connectors,
               distinct_qualifying_evses >= 2 AS passes_min_free_2
        FROM per_location
        WHERE distinct_qualifying_evses >= 2
        ORDER BY id;

        -- Summary is recomputed within the same transaction/snapshot.
        WITH candidate_locations AS MATERIALIZED (
          SELECT l.id
          FROM charging_location l
          WHERE l.is_active AND l.position IS NOT NULL
            AND ST_Intersects(l.position,
                 ST_MakeEnvelope(5.50,51.75,5.53,51.77,4326)::geography)
        ),
        matching AS (
          SELECT l.id AS location_id, e.id AS evse_id, c.id AS connector_id
          FROM candidate_locations l
          JOIN evse e ON e.location_id=l.id
          JOIN connector c ON c.evse_id=e.id
          WHERE e.is_active AND c.is_active
            AND upper(btrim(coalesce(e.status,''))) = 'AVAILABLE'
            AND upper(coalesce(c.standard,'')) = 'IEC_62196_T2_COMBO'
            AND upper(coalesce(c.power_type,'')) = 'DC'
            AND ({{watts}}) >= 150000
        ),
        grouped AS (
          SELECT location_id, count(DISTINCT evse_id) AS unique_evses,
                 count(*) AS connector_rows
          FROM matching
          GROUP BY location_id
        )
        SELECT count(*) FILTER (WHERE unique_evses >= 2)::bigint AS locations_matching_independent,
               coalesce(sum(connector_rows) FILTER (WHERE unique_evses >= 2),0)::bigint AS qualifying_connector_rows,
               coalesce(sum(unique_evses) FILTER (WHERE unique_evses >= 2),0)::bigint AS qualifying_unique_evses,
               count(*) FILTER (WHERE connector_rows > unique_evses)::bigint AS locations_with_multi_connector_matches
        FROM grouped;

        ROLLBACK;
        RESET ROLE;
        """;
    }
}
