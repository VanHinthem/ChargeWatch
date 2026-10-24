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
        geo = {"features": [{"properties": {
            "country_code": "NL", "party_id": "ABC", "source_id": "loc1",
            "availabilities": [{"available": 2, "total": 2}, {"available": 2, "total": 2}]
        }}]}
        ocpi = [{"country_code": "NL", "party_id": "ABC", "id": "loc1", "evses": [
            {"uid": "a", "status": "AVAILABLE"}, {"uid": "b", "status": "AVAILABLE"},
            {"uid": "c", "status": "CHARGING"}
        ]}]
        report = module.compare(geo, ocpi)
        self.assertEqual(1, report["stats"]["pair_differs_from_unique_evses"])

    def test_unknown_evse_status_never_counted_as_occupied(self):
        evses = module.count_unique_evses({"evses": [
            {"uid": "a", "status": "AVAILABLE"}, {"uid": "b", "status": "UNKNOWN"}
        ]})
        self.assertEqual(1, evses["unknown"])
        self.assertEqual(2, evses["total"])

    def test_unmatched_identity_not_fuzzy_matched(self):
        report = module.compare({"features": [{"id": "NL-ABC-loc1", "properties": {}}]}, [])
        self.assertEqual(1, report["stats"]["unmatched_geojson"])


if __name__ == "__main__":
    unittest.main()
