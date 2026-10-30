-- Isolated CI-only fixture. Unique source identities; no production writes.
INSERT INTO charging_location(country_code,party_id,source_id,address,city,operator_name,longitude,latitude,position,is_active)
SELECT 'NL','FTR','F'||n,'Filter Test '||n,'Oss',
       CASE WHEN n%2=0 THEN 'Fastned' ELSE 'Vattenfall InCharge' END,
       5.55+n*0.001,51.76,ST_SetSRID(ST_MakePoint(5.55+n*0.001,51.76),4326)::geography,true
FROM (SELECT generate_series(1,12) n UNION ALL SELECT unnest(ARRAY[14,16,18,20,22]) n) s;

INSERT INTO evse(location_id,source_uid,status,is_active)
SELECT id,'TEST-EVSE', CASE WHEN source_id='F2' THEN 'BLOCKED'
                           WHEN source_id='F20' THEN 'REMOVED'
                           WHEN source_id='F22' THEN 'PLANNED' ELSE 'AVAILABLE' END,
       source_id<>'F18'
FROM charging_location WHERE party_id='FTR';

INSERT INTO connector(evse_id,source_id,standard,power_type,max_electric_power,max_voltage,max_amperage,is_active)
SELECT e.id,'TEST-CONNECTOR',
       CASE WHEN substring(l.source_id from 2)::int%2=0 THEN 'IEC_62196_T2_COMBO' ELSE 'IEC_62196_T2' END,
       CASE WHEN substring(l.source_id from 2)::int%2=0 THEN 'DC' ELSE 'AC_3_PHASE' END,
       CASE WHEN l.source_id IN ('F14','F16') THEN NULL
            WHEN l.source_id='F12' THEN 50000
            WHEN substring(l.source_id from 2)::int%2=0 THEN 150000 ELSE 11000 END,
       CASE WHEN l.source_id='F14' THEN 1000 ELSE NULL END,
       CASE WHEN l.source_id='F14' THEN 150 ELSE NULL END,true
FROM evse e JOIN charging_location l ON l.id=e.location_id WHERE l.party_id='FTR';

-- Two matching CCS2 connectors on one EVSE must still count as one.
INSERT INTO connector(evse_id,source_id,standard,power_type,max_electric_power,is_active)
SELECT e.id,'SECOND-CCS2','IEC_62196_T2_COMBO','DC',150000,true
FROM evse e JOIN charging_location l ON l.id=e.location_id WHERE l.party_id='FTR' AND l.source_id='F10';

-- A CCS2/50 kW + Type2/300 kW EVSE must NEVER match CCS2 + >=150kW.
INSERT INTO connector(evse_id,source_id,standard,power_type,max_electric_power,is_active)
SELECT e.id,'INCOMPATIBLE-AC','IEC_62196_T2','AC_3_PHASE',300000,true
FROM evse e JOIN charging_location l ON l.id=e.location_id WHERE l.party_id='FTR' AND l.source_id='F12';

-- The viewport contains over 1000 unrelated *active* source locations.
-- Filter matching must occur BEFORE the viewport 1000-row cap.
INSERT INTO charging_location(country_code,party_id,source_id,address,city,
                              operator_name,longitude,latitude,position,is_active)
SELECT 'NL','FTR','FILLER-'||n,'Inactive connector test '||n,'Oss',
       NULL,5.57,51.761,ST_SetSRID(ST_MakePoint(5.57,51.761),4326)::geography,true
FROM generate_series(1,1001) n;
