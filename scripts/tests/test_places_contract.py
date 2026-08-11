from __future__ import annotations

import copy
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
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

    def assert_fixture_rejected(self, fixture_name, mutate, expected):
        errors = self.fixture_errors(fixture_name, mutate)

        self.assertTrue(
            any(expected in error for error in errors),
            f"expected={expected!r}, errors={errors!r}",
        )

    def copy_validation_inputs(self, temporary_root):
        fixture_directory = temporary_root / "fixtures/contracts/places"
        document_directory = temporary_root / "docs/contracts/domains/places"
        catalog_directory = temporary_root / "docs/contracts/rest"
        design_directory = temporary_root / "docs/designs"
        fixture_directory.mkdir(parents=True)
        document_directory.mkdir(parents=True)
        catalog_directory.mkdir(parents=True)
        design_directory.mkdir(parents=True)
        for source in (ROOT / "fixtures/contracts/places").glob("*.json"):
            shutil.copy2(source, fixture_directory / source.name)
        shutil.copy2(
            ROOT / "docs/contracts/domains/places/contract.md",
            document_directory / "contract.md",
        )
        shutil.copy2(
            ROOT / "docs/contracts/rest/catalog.json",
            catalog_directory / "catalog.json",
        )
        shutil.copy2(
            ROOT / "docs/designs/timing-jeju-backend-rdb-api-spec.md",
            design_directory / "timing-jeju-backend-rdb-api-spec.md",
        )
        return fixture_directory

    def fixture_errors(self, fixture_name, mutate):
        with tempfile.TemporaryDirectory() as directory:
            temporary_root = Path(directory)
            fixture_directory = self.copy_validation_inputs(temporary_root)
            fixture_path = fixture_directory / f"{fixture_name}.json"
            fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
            mutate(fixture)
            fixture_path.write_text(
                json.dumps(fixture, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            errors = validator.validate_contract(self.contract(), temporary_root)
        return errors

    def test_repository_places_contract_is_valid(self):
        self.assertEqual([], validator.validate_contract(self.contract(), ROOT))

    def test_rejects_required_list_success_field_omission(self):
        self.assert_fixture_rejected(
            "success",
            lambda fixture: fixture["list"]["items"][0].pop("operationsSummary"),
            "operationsSummary",
        )

    def test_rejects_unpaired_fixture_coordinates(self):
        self.assert_fixture_rejected(
            "request",
            lambda fixture: fixture["endpoints"]["list"]["query"].pop("lng"),
            "lat/lng",
        )

    def test_rejects_problem_code_outside_endpoint_condition_matrix(self):
        self.assert_fixture_rejected(
            "problem",
            lambda fixture: fixture["401"].update(code="ARBITRARY_CODE"),
            "INVALID_ACCESS_TOKEN",
        )

    def test_list_contract_and_fixture_include_data_freshness(self):
        contract = self.contract()
        success = json.loads(
            (ROOT / "fixtures/contracts/places/success.json").read_text(encoding="utf-8")
        )

        self.assertIn("schemas", contract)
        self.assertIn(
            "dataFreshness",
            contract["schemas"]["PlaceListItem"]["properties"],
        )
        self.assertIn("dataFreshness", success["list"]["items"][0])

    def test_rejects_nested_success_type_nullability_and_closed_shape_drift(self):
        mutations = (
            (
                lambda fixture: fixture["list"]["items"][0]["location"].update(
                    lat="33.458111"
                ),
                "location.lat",
            ),
            (
                lambda fixture: fixture["list"]["page"].update(offset=0),
                "정의되지 않은 필드 offset",
            ),
            (
                lambda fixture: fixture["list"]["items"][0]["dataFreshness"].pop(
                    "expiresAt"
                ),
                "dataFreshness",
            ),
            (
                lambda fixture: fixture["detail"]["contact"].pop("phone"),
                "contact",
            ),
            (
                lambda fixture: fixture["detail"]["operations"].update(
                    operatingHoursText=7
                ),
                "operations.operatingHoursText",
            ),
            (
                lambda fixture: fixture["detail"]["images"][0].update(stale=None),
                "images[0].stale",
            ),
            (
                lambda fixture: fixture["detail"]["nearbyStops"][0].update(
                    walkMinutes="4"
                ),
                "nearbyStops[0].walkMinutes",
            ),
        )
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_fixture_rejected("success", mutate, expected)

    def test_rejects_contract_schema_required_or_nullability_drift(self):
        mutations = (
            (
                lambda contract: contract["schemas"]["PlaceListItem"]["required"].remove(
                    "operationsSummary"
                ),
                "PlaceListItem",
            ),
            (
                lambda contract: contract["schemas"]["PlaceDetailResponse"].update(
                    additionalProperties=True
                ),
                "PlaceDetailResponse",
            ),
            (
                lambda contract: contract["schemas"]["NearbyStop"]["properties"][
                    "walkMinutes"
                ].pop("nullable"),
                "NearbyStop.walkMinutes",
            ),
        )
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_rejected(mutate, expected)

    def test_rejects_notion_draft_or_canonical_list_field_drift(self):
        mutations = (
            (
                lambda contract: contract["traceability"]["notion"]["endpoints"][0].update(
                    specStatus="Ready"
                ),
                "Notion",
            ),
            (
                lambda contract: contract["traceability"]["notion"]["endpoints"][0][
                    "canonicalListItemFields"
                ].remove("dataFreshness"),
                "Notion",
            ),
        )
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_rejected(mutate, expected)

    def test_rejects_canonical_schema_constraint_weakening(self):
        mutations = (
            (
                lambda contract: contract["schemas"]["PlacesListRequest"]["properties"][
                    "lat"
                ].update(maximum=900),
                "PlacesListRequest",
            ),
            (
                lambda contract: contract["schemas"]["PlacesListRequest"]["properties"][
                    "size"
                ].update(minimum=-100),
                "PlacesListRequest",
            ),
            (
                lambda contract: contract["schemas"]["DataFreshness"]["properties"][
                    "provider"
                ]["enum"].append("ARBITRARY"),
                "DataFreshness",
            ),
            (
                lambda contract: contract["schemas"]["NearbyStop"]["properties"][
                    "distanceMeters"
                ].pop("minimum"),
                "NearbyStop",
            ),
            (
                lambda contract: contract["schemas"]["ProblemDetails"]["properties"][
                    "status"
                ]["enum"].append(500),
                "ProblemDetails",
            ),
            (
                lambda contract: contract["schemas"]["NearbyStop"]["properties"][
                    "expiresAt"
                ].update(format="uri"),
                "NearbyStop",
            ),
        )
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.assert_rejected(mutate, expected)

    def test_rejects_expires_at_before_observed_at(self):
        self.assert_fixture_rejected(
            "success",
            lambda fixture: fixture["detail"]["nearbyStops"][0].update(
                expiresAt="2026-08-02T09:00:00+09:00"
            ),
            "observedAt",
        )

    def test_rejects_non_rfc3339_date_time_with_exact_field_path(self):
        invalid_values = (
            "2026-08-03 08:55:00+09:00",
            "2026-W32-1T08:55:00+09:00",
            "2026-08-03T08:55:00",
        )
        for value in invalid_values:
            with self.subTest(value=value):
                self.assert_fixture_rejected(
                    "success",
                    lambda fixture, value=value: fixture["detail"]["nearbyStops"][0].update(
                        observedAt=value
                    ),
                    "success.detail.nearbyStops[0].observedAt",
                )

    def test_rejects_invalid_uri_with_exact_field_path(self):
        invalid_values = (
            " https://example.com/image.jpg",
            "https://example.com/a b",
            "https://example.com/%ZZ",
            "https://example.com/%2",
        )
        for value in invalid_values:
            with self.subTest(value=value):
                self.assert_fixture_rejected(
                    "success",
                    lambda fixture, value=value: fixture["detail"]["images"][0].update(
                        url=value
                    ),
                    "success.detail.images[0].url",
                )

    def test_rejects_non_finite_json_numbers_during_strict_json_loading(self):
        for value, token in (
            (float("nan"), "NaN"),
            (float("inf"), "Infinity"),
            (float("-inf"), "-Infinity"),
        ):
            with self.subTest(value=value):
                self.assert_fixture_rejected(
                    "request",
                    lambda fixture, value=value: fixture["endpoints"]["list"][
                        "query"
                    ].update(lat=value),
                    f"비표준 JSON 상수 {token}",
                )

    def test_rejects_non_standard_json_constants_in_every_json_load_path(self):
        for token in ("NaN", "Infinity", "-Infinity"):
            with (
                self.subTest(path="contract", token=token),
                tempfile.TemporaryDirectory() as directory,
            ):
                contract_path = Path(directory) / "contract.json"
                contract_path.write_text(
                    CONTRACT_PATH.read_text(encoding="utf-8").replace(
                        '"ownerIssue": 83', f'"ownerIssue": {token}'
                    ),
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(ValueError, f"비표준 JSON 상수 {token}"):
                    validator.load_contract(contract_path)

            with (
                self.subTest(path="catalog", token=token),
                tempfile.TemporaryDirectory() as directory,
            ):
                temporary_root = Path(directory)
                self.copy_validation_inputs(temporary_root)
                catalog_path = temporary_root / "docs/contracts/rest/catalog.json"
                catalog_path.write_text(
                    catalog_path.read_text(encoding="utf-8").replace(
                        '"catalogVersion": "rest-contract-catalog/v1"',
                        f'"catalogVersion": {token}',
                    ),
                    encoding="utf-8",
                )
                errors = validator.validate_contract(self.contract(), temporary_root)
                self.assertTrue(
                    any(f"비표준 JSON 상수 {token}" in error for error in errors),
                    errors,
                )

            for fixture_name in ("request", "success", "problem"):
                with (
                    self.subTest(path=fixture_name, token=token),
                    tempfile.TemporaryDirectory() as directory,
                ):
                    temporary_root = Path(directory)
                    fixture_directory = self.copy_validation_inputs(temporary_root)
                    fixture_path = fixture_directory / f"{fixture_name}.json"
                    fixture_path.write_text(
                        fixture_path.read_text(encoding="utf-8").replace(
                            "{", f'{{\n  "reviewerProbe": {token},', 1
                        ),
                        encoding="utf-8",
                    )
                    errors = validator.validate_contract(self.contract(), temporary_root)
                    self.assertTrue(
                        any(f"비표준 JSON 상수 {token}" in error for error in errors),
                        errors,
                    )

    def test_cli_reports_non_standard_json_constant_in_korean_and_exits_nonzero(self):
        with tempfile.TemporaryDirectory() as directory:
            contract_path = Path(directory) / "contract.json"
            contract_path.write_text(
                CONTRACT_PATH.read_text(encoding="utf-8").replace(
                    '"ownerIssue": 83', '"ownerIssue": NaN'
                ),
                encoding="utf-8",
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(VALIDATOR_PATH),
                    "--contract",
                    str(contract_path),
                    "--root",
                    str(ROOT),
                ],
                capture_output=True,
                check=False,
                text=True,
            )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("장소 REST 계약 검사 실패", result.stderr)
        self.assertIn("비표준 JSON 상수 NaN", result.stderr)

    def test_temporal_order_uses_the_same_strict_rfc3339_parser(self):
        valid_pairs = (
            ("2026-08-03t08:55:00z", "2026-08-03T08:55:00Z"),
            ("2026-08-03T09:00:00+09:00", "2026-08-03T00:00:00Z"),
            ("2026-08-03T23:59:59-01:00", "2026-08-04T01:00:00Z"),
        )
        for observed_at, expires_at in valid_pairs:
            with self.subTest(observed_at=observed_at, expires_at=expires_at):
                errors = self.fixture_errors(
                    "success",
                    lambda fixture, observed_at=observed_at, expires_at=expires_at: fixture[
                        "detail"
                    ]["nearbyStops"][0].update(
                        observedAt=observed_at,
                        expiresAt=expires_at,
                    ),
                )
                self.assertEqual([], errors)

        self.assert_fixture_rejected(
            "success",
            lambda fixture: fixture["detail"]["nearbyStops"][0].update(
                observedAt="2026-08-04t00:00:00z",
                expiresAt="2026-08-03T23:59:59Z",
            ),
            "observedAt은 expiresAt보다 늦을 수 없습니다",
        )

    def test_catalog_contract_and_document_keep_all_readiness_not_ready(self):
        contract = self.contract()
        nearby_readiness = contract["nearbyStops"]["readiness"]
        document = (
            ROOT / "docs/contracts/domains/places/contract.md"
        ).read_text(encoding="utf-8")

        self.assertEqual(
            {
                "metadata": "not-ready",
                "example": "not-ready",
                "implementation": "not-ready",
            },
            nearby_readiness,
        )
        self.assertIn(
            "readiness: metadata=not-ready, example=not-ready, implementation=not-ready",
            document,
        )
        self.assertNotIn("Metadata/Example Ready", document)

        with tempfile.TemporaryDirectory() as directory:
            temporary_root = Path(directory)
            self.copy_validation_inputs(temporary_root)
            document_path = temporary_root / "docs/contracts/domains/places/contract.md"
            document_path.write_text(
                document_path.read_text(encoding="utf-8").replace(
                    "readiness: metadata=not-ready, example=not-ready, implementation=not-ready",
                    "readiness: metadata=ready, example=ready, implementation=not-ready",
                ),
                encoding="utf-8",
            )
            errors = validator.validate_contract(contract, temporary_root)
            self.assertTrue(any("readiness 문서" in error for error in errors), errors)

    def test_query_is_trimmed_before_one_to_one_hundred_character_validation(self):
        contract = self.contract()
        query_schema = contract["schemas"]["PlacesListRequest"]["properties"]["query"]

        self.assertEqual("trim", query_schema.get("normalization"))
        self.assert_fixture_rejected(
            "request",
            lambda fixture: fixture["endpoints"]["list"]["query"].update(query="   "),
            "request.list.query.query",
        )
        for value in ("  성산  ", f" {'가' * 100} "):
            with self.subTest(value=value):
                errors = self.fixture_errors(
                    "request",
                    lambda fixture, value=value: fixture["endpoints"]["list"]["query"].update(
                        query=value
                    ),
                )
                self.assertEqual([], errors)
        self.assert_fixture_rejected(
            "request",
            lambda fixture: fixture["endpoints"]["list"]["query"].update(
                query=f" {'가' * 101} "
            ),
            "request.list.query.query",
        )

    def test_operations_schema_fixture_and_api_spec_have_four_required_fields(self):
        expected = {
            "operatingHoursText",
            "closedDaysText",
            "parkingText",
            "admissionFeeText",
        }
        contract = self.contract()
        success = json.loads(
            (ROOT / "fixtures/contracts/places/success.json").read_text(encoding="utf-8")
        )
        api_spec = (
            ROOT / "docs/designs/timing-jeju-backend-rdb-api-spec.md"
        ).read_text(encoding="utf-8")

        self.assertEqual(expected, set(contract["schemas"]["Operations"]["required"]))
        self.assertEqual(expected, set(success["detail"]["operations"]))
        self.assertTrue(all(f'"{field}"' in api_spec for field in expected))

    def test_external_version_drift_keeps_catalog_metadata_and_example_not_ready(self):
        catalog = json.loads(
            (ROOT / "docs/contracts/rest/catalog.json").read_text(encoding="utf-8")
        )
        places = next(item for item in catalog["domainContracts"] if item["issue"] == 83)

        self.assertEqual("1.0.0", places["versions"]["local"])
        self.assertEqual("not-linked", places["versions"]["notion"])
        self.assertEqual("not-linked", places["versions"]["figma"])
        self.assertEqual("not-ready", places["readiness"]["metadata"]["status"])
        self.assertEqual("not-ready", places["readiness"]["example"]["status"])
        self.assertEqual("not-ready", places["readiness"]["implementation"]["status"])

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
                "#66 증거 전",
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
                lambda c: c["endpoints"][0]["problems"].pop(),
                "condition/status/code/type matrix",
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
