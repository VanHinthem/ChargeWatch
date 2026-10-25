import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "validate_ndw_evse.py"
spec = importlib.util.spec_from_file_location("validate_ndw_evse", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NdwEvseValidationTests(unittest.TestCase):
    def test_one_evse_two_connectors_count_once(self):
        loc = {"evses": [{"uid": "x", "status": "AVAILABLE", "connectors": [{"id": "1"}, {"id": "2"}]}]}
        self.assertEqual({"total": 1, "available": 1}, {k: module.count_unique_evses(loc)[k] for k in ("total", "available")})

    def test_duplicate_evse_uid_not_double_counted(self):
        loc = {"evses": [{"uid": "x", "status": "AVAILABLE"}, {"uid": "x", "status": "AVAILABLE"}]}
        self.assertEqual(1, module.count_unique_evses(loc)["total"])

    def test_same_category_pair_not_proof_of_unique_evse_total(self):
        geo = {"features": [{"id": "NL-ABC-loc1", "properties": {
            "country": "NL",
            "availabilities": [{"available": 2, "total": 2}, {"available": 2, "total": 2}]
        }}]}
        ocpi = [{"country_code": "NL", "party_id": "ABC", "id": "loc1", "evses": [
            {"uid": "a", "status": "AVAILABLE"}, {"uid": "b", "status": "AVAILABLE"},
            {"uid": "c", "status": "CHARGING"}
        ]}]
        report = module.compare(geo, ocpi)
        self.assertEqual(1, report["stats"]["pair_differs_from_unique_evses"])

    def test_summed_categories_can_match_unique_evses_while_max_does_not(self):
        geo = {"features": [{"id": "NL-ABC-loc2", "properties": {"country": "NLD",
            "availabilities": [{"available": 1, "total": 1}, {"available": 1, "total": 1}]}}]}
        ocpi = [{"country_code": "NL", "party_id": "ABC", "id": "loc2", "evses": [
            {"uid": "a", "status": "AVAILABLE"}, {"uid": "b", "status": "AVAILABLE"}]}]
        stats = module.compare(geo, ocpi)["stats"]
        self.assertEqual(1, stats["multi_sum_matches"])
        self.assertEqual(1, stats["multi_max_differs"])

    def test_status_timing_mismatch_does_not_invalidate_total_count(self):
        geo = {"features": [{"id": "NL-ABC-loc3", "properties": {"country": "NLD",
            "availabilities": [{"available": 1, "total": 2}]}}]}
        ocpi = [{"country_code": "NL", "party_id": "ABC", "id": "loc3", "evses": [
            {"uid": "a", "status": "CHARGING"}, {"uid": "b", "status": "CHARGING"}]}]
        stats = module.compare(geo, ocpi)["stats"]
        self.assertEqual(1, stats["sum_total_matches"])
        self.assertEqual(1, stats["sum_total_matches_available_differs"])
        self.assertEqual(1, stats["sum_differs"])

    def test_capacity_delta_reports_overcount(self):
        geo = {"features": [{"id": "NL-ABC-loc4", "properties": {"country": "NLD",
            "availabilities": [{"available": 1, "total": 2}, {"available": 1, "total": 2}]}}]}
        ocpi = [{"country_code": "NL", "party_id": "ABC", "id": "loc4", "evses": [
            {"uid": "a", "status": "AVAILABLE"}, {"uid": "b", "status": "AVAILABLE"}]}]
        report = module.compare(geo, ocpi)
        self.assertEqual(1, report["capacity_difference_by_delta"]["2"])
        self.assertEqual(1, report["capacity_difference_by_party"]["ABC"])

    def test_unknown_evse_status_never_counted_as_occupied(self):
        evses = module.count_unique_evses({"evses": [
            {"uid": "a", "status": "AVAILABLE"}, {"uid": "b", "status": "UNKNOWN"}
        ]})
        self.assertEqual(1, evses["unknown"])
        self.assertEqual(2, evses["total"])

    def test_ndw_compound_identifier_preserves_hyphenated_location_id(self):
        self.assertEqual(("NL", "EFL", "loc-123"), module.geo_key({"id": "NL-EFL-loc-123", "properties": {"country": "NL"}}))

    def test_iso3_country_and_ocpi_country_id_match(self):
        self.assertEqual(("NL", "EFL", "672e052a98865f14efaf9429"), module.geo_key({"id": "NL-EFL-672e052a98865f14efaf9429", "properties": {"country": "NLD", "cpo_id": "EFL"}}))

    def test_unmatched_identity_not_fuzzy_matched(self):
        report = module.compare({"features": [{"id": "NL-ABC-loc1", "properties": {}}]}, [])
        self.assertEqual(1, report["stats"]["unmatched_geojson"])


if __name__ == "__main__":
    unittest.main()
