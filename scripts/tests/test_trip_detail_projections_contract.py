from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class TripDetailProjectionsContractTest(unittest.TestCase):
    def test_projection_이전_receipt는_POST와_Day_PUT에서만_허용한다(self) -> None:
        """배포 전 두 mutation 원본은 보존하며 최신 GET에는 child를 필수로 유지한다."""
        from scripts.validate_trips_contract import _validate_value
        contract = json.loads((ROOT / "docs/contracts/domains/trips/contract.json").read_text())
        schemas = contract["schemas"]
        old = json.loads((ROOT / "fixtures/contracts/trips/legacy-create-replay-v11.json").read_text())
        for method in ["POST", "PUT"]:
            endpoint = next(e for e in contract["endpoints"] if e["method"] == method)
            errors = []
            _validate_value(old, schemas[endpoint["successSchema"]], schemas, method, errors)
            self.assertEqual([], errors)
        errors = []
        _validate_value(old, schemas["TripDetail"], schemas, "GET", errors)
        self.assertTrue(errors)
        self.assertEqual(3, len(schemas["TripCreateResponse"]["oneOf"]))
        self.assertEqual(2, len(schemas["TripDayActivityWindowsResponse"]["oneOf"]))

    def test_detail_has_required_closed_transport_pair_and_accommodation_array(self) -> None:
        """여행 상세는 미입력 여부와 관계없이 교통 두 슬롯과 숙소 배열을 제공한다."""
        schemas = self.schemas("trips")
        detail = schemas["TripDetail"]
        self.assertIn("transportEvents", detail["required"])
        self.assertIn("accommodations", detail["required"])
        pair = schemas[detail["properties"]["transportEvents"]["$ref"]]
        self.assertEqual(["arrival", "departure"], pair["required"])
        self.assertFalse(pair["additionalProperties"])
        self.assertFalse(pair["nullable"])
        for key in ("arrival", "departure"):
            self.assertTrue(pair["properties"][key]["nullable"])
        accommodations = detail["properties"]["accommodations"]
        self.assertEqual("array", accommodations["type"])
        self.assertFalse(accommodations["nullable"])

    def test_read_children_reuse_exact_write_payload_semantics(self) -> None:
        """교통과 숙소 상세 조회의 필드·enum·nullability는 기존 저장 계약과 정확히 같다."""
        schemas = self.schemas("trips")
        detail = schemas["TripDetail"]
        pair = schemas[detail["properties"]["transportEvents"]["$ref"]]
        for key in ("arrival", "departure"):
            self.assertEqual(
                self.schemas("preferences-transport")["TransportEventRequest"],
                schemas[pair["properties"][key]["$ref"]],
            )
        self.assertEqual(
            self.schemas("accommodations")["Accommodation"],
            schemas[detail["properties"]["accommodations"]["items"]["$ref"]],
        )

    @staticmethod
    def schemas(domain: str) -> dict:
        return json.loads(
            (ROOT / "docs/contracts/domains" / domain / "contract.json").read_text()
        )["schemas"]


if __name__ == "__main__":
    unittest.main()
