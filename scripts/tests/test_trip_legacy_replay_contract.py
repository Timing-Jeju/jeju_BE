"""배포 전 여행 생성 receipt의 원본 응답 호환성을 검증한다."""
import copy
import json
import unittest
from pathlib import Path
from scripts.validate_trips_contract import _validate_value

ROOT = Path(__file__).resolve().parents[2]

class TripLegacyReplayContractTest(unittest.TestCase):
    def test_post만_과거와_최신_snapshot을_정확히_허용한다(self):
        """과거 POST 원본은 허용하되 최신 GET 필수 필드를 약화하지 않는다."""
        contract = json.loads((ROOT / "docs/contracts/domains/trips/contract.json").read_text())
        schemas = contract["schemas"]
        post = next(e for e in contract["endpoints"] if e["method"] == "POST")
        self.assertEqual("TripCreateResponse", post["successSchema"])
        old = json.loads((ROOT / "fixtures/contracts/trips/legacy-create-replay.json").read_text())
        current = json.loads((ROOT / "fixtures/contracts/trips/success.json").read_text())["create"]["body"]
        for body in [old, current]:
            errors = []
            _validate_value(body, schemas[post["successSchema"]], schemas, "POST", errors)
            self.assertEqual([], errors)
        errors = []
        _validate_value(old, schemas["TripDetail"], schemas, "GET", errors)
        self.assertTrue(errors)
        mixed = copy.deepcopy(old)
        mixed["days"][0]["activityStartTime"] = None
        errors = []
        _validate_value(mixed, schemas[post["successSchema"]], schemas, "POST", errors)
        self.assertTrue(errors)

    def test_과거_schema는_활동창_이전의_닫힌_필드집합을_유지한다(self):
        """과거 응답 분기는 활동창 필드를 선택적으로 섞어 허용하지 않는다."""
        schemas = json.loads((ROOT / "docs/contracts/domains/trips/contract.json").read_text())["schemas"]
        self.assertEqual(["dayId", "dayNo", "date"], schemas["TripDayLegacyV1"]["required"])
        self.assertFalse(schemas["TripDayLegacyV1"]["additionalProperties"])
        self.assertEqual({"$ref": "TripDayLegacyV1", "nullable": False}, schemas["TripDetailLegacyV1"]["properties"]["days"]["items"])
