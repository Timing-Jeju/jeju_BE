from __future__ import annotations

import copy
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR_PATH = ROOT / "scripts" / "validate_places_contract.py"
CONTRACT_PATH = ROOT / "docs/contracts/domains/places/contract.json"


spec = importlib.util.spec_from_file_location("validate_places_contract", VALIDATOR_PATH)
validator = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(validator)


class PlacesContractTest(unittest.TestCase):
    def contract(self):
        return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))

    def assert_rejected(self, mutate, expected):
        contract = copy.deepcopy(self.contract())
        mutate(contract)

        errors = validator.validate_contract(contract, ROOT)

        self.assertTrue(
            any(expected in error for error in errors),
            f"expected={expected!r}, errors={errors!r}",
        )

    def test_repository_places_contract_is_valid(self):
        self.assertEqual([], validator.validate_contract(self.contract(), ROOT))

    def test_rejects_endpoint_identity_or_common_contract_drift(self):
        mutations = (
            (lambda c: c["endpoints"].pop(), "두 endpoint"),
            (
                lambda c: c["endpoints"][0].update(path="/api/v1/other"),
                "method/path",
            ),
            (
                lambda c: c["endpoints"][0]["auth"].update(mode="required"),
                "Optional 인증",
            ),
            (
                lambda c: c.update(inherits="other-contract"),
                "#72 공통 계약",
            ),
        )
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_rejected(mutate, expected)

    def test_rejects_cursor_filter_and_geo_boundary_drift(self):
        mutations = (
            (
                lambda c: c["endpoints"][0]["pagination"].update(
                    filterChange="reuse"
                ),
                "필터 변경",
            ),
            (
                lambda c: c["endpoints"][0]["pagination"].update(
                    tieBreaker="name ASC"
                ),
                "tie-breaker",
            ),
            (
                lambda c: c["endpoints"][0]["query"]["lat"].update(maximum=91),
                "lat/lng/radiusMeters",
            ),
            (
                lambda c: c["endpoints"][0]["query"]["radiusMeters"].update(
                    maximum=100_001
                ),
                "lat/lng/radiusMeters",
            ),
        )
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_rejected(mutate, expected)

    def test_rejects_anonymous_saved_shape_or_response_consistency_drift(self):
        mutations = (
            (
                lambda c: c["anonymousPersonalization"]["list"].update(
                    memo="omitted"
                ),
                "익명 saved/memo/tags",
            ),
            (
                lambda c: c["responseConsistency"]["list"].remove(
                    "recommendedStayMinutes"
                ),
                "목록/상세 일관성",
            ),
            (
                lambda c: c["responseConsistency"]["detail"].remove("images"),
                "목록/상세 일관성",
            ),
        )
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_rejected(mutate, expected)

    def test_rejects_field_owner_or_freshness_provenance_drift(self):
        mutations = (
            (
                lambda c: c["fieldOwnership"]["recommendedStayMinutes"].update(
                    owner="TourAPI"
                ),
                "필드 owner",
            ),
            (
                lambda c: c["fieldOwnership"]["nearbyStops"].update(
                    provider="TourAPI"
                ),
                "필드 owner",
            ),
            (
                lambda c: c["fieldOwnership"]["images"].pop("expiresAt"),
                "provider/observedAt/expiresAt/stale",
            ),
        )
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_rejected(mutate, expected)

    def test_rejects_nearby_stops_schema_eligibility_sort_or_readiness_drift(self):
        mutations = (
            (
                lambda c: c["nearbyStops"]["itemFields"].remove("expiresAt"),
                "nearbyStops itemFields",
            ),
            (
                lambda c: c["nearbyStops"].update(emptyWhen="stale-only"),
                "eligible 없음",
            ),
            (
                lambda c: c["nearbyStops"]["inclusion"].update(staleOnly=False),
                "stale-only",
            ),
            (
                lambda c: c["nearbyStops"]["sort"].reverse(),
                "nearbyStops 정렬",
            ),
            (
                lambda c: c["nearbyStops"].update(maxItems=6),
                "최대 5개",
            ),
            (
                lambda c: c["nearbyStops"].update(deduplicateBy="nodeId"),
                "stopId 중복",
            ),
            (
                lambda c: c["nearbyStops"]["owners"].update(
                    implementationIssue=83
                ),
                "#37/#66",
            ),
            (
                lambda c: c["nearbyStops"]["readiness"].update(
                    implementation="ready"
                ),
                "#66 완료 전",
            ),
        )
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_rejected(mutate, expected)

    def test_rejects_freshness_reason_anywhere(self):
        self.assert_rejected(
            lambda c: c["nearbyStops"].update(freshnessReason="expired"),
            "freshnessReason",
        )

    def test_rejects_error_matrix_problem_details_or_traceability_drift(self):
        mutations = (
            (
                lambda c: c["errors"].pop("503"),
                "오류 matrix",
            ),
            (
                lambda c: c["problemExample"].update(detail="failed"),
                "한국어 Problem Details",
            ),
            (
                lambda c: c["traceability"]["figma"].update(nodeId="0-0"),
                "Figma",
            ),
            (
                lambda c: c["traceability"]["notion"]["endpoints"].pop(),
                "Notion",
            ),
        )
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_rejected(mutate, expected)

    def test_rejects_secret_or_personal_data_terms_recursively(self):
        mutations = (
            lambda c: c.update(rawToken="Bearer actual-token"),
            lambda c: c.update(email="real@example.com"),
            lambda c: c.update(apiKey="actual-key"),
            lambda c: c.update(providerPayload={"raw": True}),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                self.assert_rejected(mutate, "민감정보")


if __name__ == "__main__":
    unittest.main()
