-- NDW GeoJSON Oss samples vs ChargeWatch OCPI catalog (read-only).
-- Run in psql against the existing ChargeWatch database. Zero writes.
-- Exact ID matches require verification: NL-<cpo_id>-<rest> is an observed pattern,
-- NOT a confirmed NDW guarantee. Spatial proximity is evidence only, never identity.
BEGIN TRANSACTION READ ONLY;
WITH geo(feature_id, cpo_id, address, lon, lat, reported_available, reported_total) AS (
 VALUES
 ('NL-505-91056291', '505', 'Schutterij 32', 5.511838, 51.782173, 1, 1),
 ('NL-505-91275531', '505', 'Oijenseweg 230', 5.518126, 51.781818, 1, 1),
 ('NL-NUO-0bf8edee-f912-4891-8b4c-ad709ff523fb', 'NUO', 'Slotgracht 65', 5.517754, 51.781479, 1, 2),
 ('NL-NUO-402fa3d671aef15471b2f7a47f2d12e7', 'NUO', 'Arena 2', 5.520364, 51.782150, 0, 2),
 ('NL-NUO-44efa5d013fa9b11279ad0b7190450fa', 'NUO', 'Sportlaan 31', 5.521772, 51.781704, 2, 2),
 ('NL-NUO-48f01e6d-8273-4387-ba93-91c11ead2c03', 'NUO', 'Sportlaan 13', 5.522135, 51.782295, 2, 2),
 ('NL-NUO-bc499ff6-3e6e-4f98-8dec-41f7252cc7c0', 'NUO', 'Banier 28', 5.520650, 51.782650, 2, 4),
 ('NL-NUO-fecd71a7f2150d35a1300626c2209a09', 'NUO', 'Podium 1', 5.521816, 51.781677, 2, 2),
 ('NL-EFL-650dc75ff063f600200cc198', 'EFL', 'Lekstraat 13', 5.549473, 51.785358, 1, 2),
 ('NL-LNT-3966410', 'LNT', 'Merwedestraat 54', 5.550586, 51.787689, 0, 1)
),
normalized AS (
 SELECT *, substring(feature_id FROM char_length('NL-' || cpo_id || '-') + 1) AS possible_source_id
 FROM geo
),
candidate AS (
 SELECT g.feature_id, g.cpo_id, g.address AS ndw_address,
        g.reported_available, g.reported_total,
        g.possible_source_id, l.id AS db_location_id,
        l.country_code, l.party_id, l.source_id, l.address AS db_address, l.city,
        round(ST_Distance(l.position, ST_SetSRID(ST_MakePoint(g.lon,g.lat),4326)::geography)::numeric, 1) AS distance_meters,
        (l.country_code='NL' AND l.party_id=g.cpo_id AND l.source_id=g.possible_source_id) AS id_exact_candidate,
        (lower(trim(l.address)) = lower(trim(g.address))) AS address_equal
 FROM normalized g
 LEFT JOIN charging_location l ON
       (l.country_code='NL' AND l.party_id=g.cpo_id AND l.source_id=g.possible_source_id)
       OR (l.position IS NOT NULL AND ST_DWithin(
            l.position, ST_SetSRID(ST_MakePoint(g.lon,g.lat),4326)::geography, 30))
),
evse_totals AS (
 SELECT location_id,
        count(*)::integer AS evse_count,
        count(*) FILTER (WHERE upper(status)='AVAILABLE')::integer AS available_evses,
        string_agg(DISTINCT upper(status), ', ' ORDER BY upper(status)) AS statuses
 FROM evse
 GROUP BY location_id
)
SELECT c.feature_id, c.possible_source_id, c.db_location_id,
       c.country_code, c.party_id, c.source_id,
       c.ndw_address, c.db_address, c.city, c.distance_meters,
       c.id_exact_candidate, c.address_equal,
       c.reported_available, c.reported_total,
       e.evse_count, e.available_evses, e.statuses
FROM candidate c LEFT JOIN evse_totals e ON e.location_id=c.db_location_id
ORDER BY c.feature_id, c.id_exact_candidate DESC NULLS LAST,
         c.distance_meters NULLS LAST, c.db_location_id;
COMMIT;
