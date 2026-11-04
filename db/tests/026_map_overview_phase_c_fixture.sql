-- ChargeWatch #113 phase C: isolated deterministic HTTP/Npgsql fixture.
-- Uses standard schema/migrations; no production or NAS database writes.
-- 625 individual level-14 canonical cells near Oss (25 x 25).
WITH origin AS (
    SELECT floor((ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(5.53,51.75),4326),3857))
        + 20037508.342789244)/(40075016.68557849/16384.0))::bigint AS x,
           floor((20037508.342789244
        - ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(5.53,51.75),4326),3857)))
        /(40075016.68557849/16384.0))::bigint AS y
), positions AS (
    SELECT xx, yy,
       ST_Transform(ST_SetSRID(ST_MakePoint(
          (origin.x + xx + 0.5)*(40075016.68557849/16384.0) - 20037508.342789244,
          20037508.342789244 - (origin.y + yy + 0.5)*(40075016.68557849/16384.0)
       ),3857),4326) AS point
    FROM origin CROSS JOIN generate_series(0,24) xx CROSS JOIN generate_series(0,24) yy
)
INSERT INTO charging_location(country_code,party_id,source_id,address,city,
    operator_name,longitude,latitude,position,is_active)
SELECT 'NL','OVR','G-'||xx||'-'||yy,'Grid Fixture','Oss',
    CASE WHEN (xx+yy)%2=0 THEN 'Fastned' ELSE 'Vattenfall InCharge' END,
    ST_X(point),ST_Y(point),point::geography,true
FROM positions;

-- Extra 1,001 active locations share coordinates; never silently truncate.
INSERT INTO charging_location(country_code,party_id,source_id,address,city,
    operator_name,longitude,latitude,position,is_active)
SELECT 'NL','OVR','FILLER-'||n,'Coincident fixture','Oss',NULL,
    5.53,51.75,ST_SetSRID(ST_MakePoint(5.53,51.75),4326)::geography,true
FROM generate_series(1,1001) n;

-- A second separately identified location inside grid cell G-0-0.
INSERT INTO charging_location(country_code,party_id,source_id,address,city,
    operator_name,longitude,latitude,position,is_active)
SELECT 'NL','OVR','DUPLICATE','Same coordinate','Oss','Fastned',
    longitude,latitude,position,true
FROM charging_location WHERE party_id='OVR' AND source_id='G-0-0';

INSERT INTO charging_location(country_code,party_id,source_id,address,city,
    operator_name,longitude,latitude,position,is_active)
VALUES
 ('NL','OVR','SINGLE-FREE','Single Free','Oss','Fastned',5.310,51.580,
    ST_SetSRID(ST_MakePoint(5.310,51.580),4326)::geography,true),
 ('NL','OVR','SINGLE-ZERO','Single Zero','Oss','Vattenfall InCharge',5.312,51.580,
    ST_SetSRID(ST_MakePoint(5.312,51.580),4326)::geography,true),
 ('NL','OVR','SINGLE-BLOCKED','Single Blocked','Oss','Fastned',5.314,51.580,
    ST_SetSRID(ST_MakePoint(5.314,51.580),4326)::geography,true);

INSERT INTO evse(location_id,source_uid,status,is_active)
SELECT id,'G-EVSE',
    CASE (split_part(source_id,'-',2)::int + split_part(source_id,'-',3)::int)%4
       WHEN 0 THEN 'AVAILABLE'
       WHEN 1 THEN 'CHARGING'
       WHEN 2 THEN 'PLANNED'
       ELSE 'OUTOFORDER'
    END,true
FROM charging_location WHERE party_id='OVR' AND source_id LIKE 'G-%';

INSERT INTO evse(location_id,source_uid,status,is_active)
SELECT id,'DUP-EVSE','CHARGING',true
FROM charging_location WHERE party_id='OVR' AND source_id='DUPLICATE';

INSERT INTO evse(location_id,source_uid,status,is_active)
SELECT id,'SINGLE-EVSE',CASE source_id
    WHEN 'SINGLE-FREE' THEN 'AVAILABLE'
    ELSE 'BLOCKED' END,true
FROM charging_location WHERE party_id='OVR' AND source_id IN ('SINGLE-FREE','SINGLE-BLOCKED');

INSERT INTO evse(location_id,source_uid,status,is_active)
SELECT id,'SINGLE-SECOND','CHARGING',true
FROM charging_location WHERE party_id='OVR' AND source_id='SINGLE-FREE';

INSERT INTO connector(evse_id,source_id,standard,power_type,max_electric_power,is_active)
SELECT e.id,'CONNECTOR',
    CASE WHEN (split_part(l.source_id,'-',2)::int + split_part(l.source_id,'-',3)::int)%2=0
         THEN 'IEC_62196_T2_COMBO' ELSE 'IEC_62196_T2' END,
    CASE WHEN (split_part(l.source_id,'-',2)::int + split_part(l.source_id,'-',3)::int)%2=0
         THEN 'DC' ELSE 'AC_3_PHASE' END,
    CASE WHEN (split_part(l.source_id,'-',2)::int + split_part(l.source_id,'-',3)::int)%2=0
         THEN 150000 ELSE 11000 END,true
FROM evse e JOIN charging_location l ON e.location_id=l.id
WHERE l.party_id='OVR' AND l.source_id LIKE 'G-%';

-- Second connector on the same EVSE must NOT count as a second free EVSE.
INSERT INTO connector(evse_id,source_id,standard,power_type,max_electric_power,is_active)
SELECT e.id,'SECOND-CCS2','IEC_62196_T2_COMBO','DC',150000,true
FROM evse e JOIN charging_location l ON e.location_id=l.id
WHERE l.party_id='OVR' AND l.source_id='G-0-0';

-- Canonical grid-14 cross: four epsilon-offset quadrants and the exact corner.
-- Production SQL and the independent HTTP oracle must assign each precisely once.
WITH origin AS (
    SELECT floor((ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(5.53,51.75),4326),3857))
        + 20037508.342789244)/(40075016.68557849/16384.0))::bigint AS x,
           floor((20037508.342789244
        - ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(5.53,51.75),4326),3857)))
        /(40075016.68557849/16384.0))::bigint AS y
), edge_points AS (
    SELECT name,ST_Transform(ST_SetSRID(ST_MakePoint(
        (origin.x + 5)*(40075016.68557849/16384.0) - 20037508.342789244 + dx,
        20037508.342789244 - (origin.y + 5)*(40075016.68557849/16384.0) + dy
    ),3857),4326) AS point
    FROM origin CROSS JOIN (VALUES
        ('NW',-0.2,0.2),('NE',0.2,0.2),('SW',-0.2,-0.2),
        ('SE',0.2,-0.2),('CORNER',0.0,0.0)) v(name,dx,dy)
)
INSERT INTO charging_location(country_code,party_id,source_id,address,city,
    operator_name,longitude,latitude,position,is_active)
SELECT 'NL','OVR','EDGE-'||name,'Grid boundary '||name,'Oss',
    NULL,ST_X(point),ST_Y(point),point::geography,true FROM edge_points;
