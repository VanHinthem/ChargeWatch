internal static class Q4cPowerBoundaryExporter
{
    // Reuse the exact production expression, without another formula copy.
    public static string Create()
    {
        var effective = MapFilter.EffectiveWattsSql;
        return $$"""
        -- Q4c-02b: production EffectiveWattsSql boundary and derived-power checks.
        -- Read-only. The input VALUES are synthetic; real connector table is only read.
        SET ROLE chargewatch_read;
        BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
        SET LOCAL statement_timeout = '5s';
        SET LOCAL lock_timeout = '1s';
        SET LOCAL jit = off;

        WITH fixtures(name,max_electric_power,max_voltage,max_amperage,power_type,expected_watts) AS (
          VALUES
            ('explicit_49999',49999::bigint,NULL::integer,NULL::integer,'DC'::text,49999::bigint),
            ('explicit_50000',50000::bigint,NULL::integer,NULL::integer,'DC'::text,50000::bigint),
            ('explicit_149999',149999::bigint,NULL::integer,NULL::integer,'DC'::text,149999::bigint),
            ('explicit_150000',150000::bigint,NULL::integer,NULL::integer,'DC'::text,150000::bigint),
            ('explicit_299999',299999::bigint,NULL::integer,NULL::integer,'DC'::text,299999::bigint),
            ('explicit_300000',300000::bigint,NULL::integer,NULL::integer,'DC'::text,300000::bigint),
            ('derived_dc_49999',NULL::bigint,499::integer,100::integer,'DC'::text,49900::bigint),
            ('derived_dc_50000',NULL::bigint,500::integer,100::integer,'DC'::text,50000::bigint),
            ('derived_dc_150000',NULL::bigint,1000::integer,150::integer,'DC'::text,150000::bigint),
            ('derived_dc_300000',NULL::bigint,1000::integer,300::integer,'DC'::text,300000::bigint),
            ('derived_ac1',NULL::bigint,230::integer,16::integer,'AC_1_PHASE'::text,3680::bigint),
            ('derived_ac3_low_voltage',NULL::bigint,230::integer,16::integer,'AC_3_PHASE'::text,11040::bigint),
            ('explicit_precedence',50000::bigint,1000::integer,300::integer,'DC'::text,50000::bigint),
            ('zero_fallback',0::bigint,500::integer,100::integer,'DC'::text,50000::bigint),
            ('missing_data',NULL::bigint,NULL::integer,NULL::integer,'DC'::text,NULL::bigint)
        ),
        calculated AS (
          SELECT c.name,c.expected_watts,({{effective}}) AS actual_watts
          FROM fixtures c
        ),
        classified AS (
          SELECT name,expected_watts,actual_watts,
                 actual_watts IS NOT DISTINCT FROM expected_watts AS value_ok,
                 (actual_watts > 0 AND actual_watts < 50000) IS TRUE AS lt50,
                 (actual_watts >= 50000) IS TRUE AS gte50,
                 (actual_watts >= 150000) IS TRUE AS gte150,
                 (actual_watts >= 300000) IS TRUE AS gte300
          FROM calculated
        )
        SELECT *,bool_and(value_ok) OVER () AS all_values_ok
        FROM classified ORDER BY name;

        -- Read actual production distribution without retrieving large connector payloads.
        WITH powers AS (
          SELECT c.id, c.max_electric_power,
                 ({{effective}}) AS effective_watts
          FROM connector c
          WHERE c.is_active
        )
        SELECT
          count(*) AS active_connectors,
          count(*) FILTER (WHERE max_electric_power > 0) AS explicit_positive,
          count(*) FILTER (WHERE (max_electric_power IS NULL OR max_electric_power <= 0)
                             AND effective_watts > 0) AS calculated_positive,
          count(*) FILTER (WHERE effective_watts IS NULL) AS unknown_power,
          count(*) FILTER (WHERE effective_watts = 50000) AS exactly_50kw,
          count(*) FILTER (WHERE effective_watts = 150000) AS exactly_150kw,
          count(*) FILTER (WHERE effective_watts = 300000) AS exactly_300kw
        FROM powers;

        ROLLBACK;
        RESET ROLE;
        """;
    }
}
