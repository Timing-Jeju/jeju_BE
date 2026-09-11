import copy
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/contracts/domains/weather-forecast/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
TEMPLATE = ROOT / "docs/contracts/rest/endpoint-template.json"
FIXTURES = ROOT / "fixtures/contracts/weather-forecast"
VALIDATOR = ROOT / "scripts/validate_weather_forecast_contract.py"
COMMON_VALIDATOR = ROOT / "scripts/validate_rest_contracts.py"
RDB_SPEC = ROOT / "docs/designs/timing-jeju-backend-rdb-api-spec.md"
INITIAL_SCHEMA = ROOT / "supabase/migrations/20260728000000_initial_public_schema.sql"


class WeatherForecastContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        spec = importlib.util.spec_from_file_location("weather_contract_validator", VALIDATOR)
        assert spec is not None and spec.loader is not None
        cls.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.validator)
        common_spec = importlib.util.spec_from_file_location(
            "weather_common_contract_validator", COMMON_VALIDATOR
        )
        assert common_spec is not None and common_spec.loader is not None
        cls.common_validator = importlib.util.module_from_spec(common_spec)
        common_spec.loader.exec_module(cls.common_validator)

    def test_identity_endpoint_and_common_inheritance_are_exact(self) -> None:
        """위치 비수집 v2 계약과 오류·근거의 변경 경계를 검증한다."""
        self.assertEqual("timing-jeju-weather-forecast-contract/v1", self.contract["schemaVersion"])
        self.assertEqual("2.0.0", self.contract["contractVersion"])
        self.assertEqual("2.0.0", self.contract["sourceSpecVersion"])
        self.assertEqual("timing-jeju-rest-contract/v1", self.contract["inherits"])
        self.assertEqual(94, self.contract["ownerIssue"])
        self.assertEqual([222], self.contract["implementationIssues"])
        self.assertEqual(
            {("GET", "/api/v1/weather/forecast")},
            {(item["method"], item["path"]) for item in self.contract["endpoints"]},
        )

    def test_query_requires_exactly_one_selector_and_datetime(self) -> None:
        """날씨는 GPS 대신 계획 selector 하나와 시각을 받는다."""
        query = self.contract["schemas"]["WeatherForecastQuery"]
        self.assertIs(False, query["additionalProperties"])
        self.assertEqual(["dateTime"], query["required"])
        self.assertEqual({"regionCode", "placeId", "tripItemId", "dateTime"}, set(query["properties"]))
        self.assertEqual([{"required": [key]} for key in ("regionCode", "placeId", "tripItemId")], query["oneOf"])
        for key, value in [("regionCode", "jeju-si"), ("placeId", "20000000-0000-4000-8000-000000000001"), ("tripItemId", "60000000-0000-4000-8000-000000000001")]:
            errors = []
            self.validator._validate_value({key: value, "dateTime": "2026-08-03T14:00:00+09:00"}, query, self.contract["schemas"], "query", errors)
            self.assertEqual([], errors)

    def test_grid_base_horizon_version_and_selection_are_closed(self) -> None:
        """기존 공공 사실과 새 계약의 검증 불가 상태를 정확히 유지한다."""
        self.assertEqual(
            {
                "projection": "KMA DFS 5km Lambert conformal conic",
                "rounding": "floor(projectedCoordinate + 0.5)",
                "nx": {"minimum": 1, "maximum": 149},
                "ny": {"minimum": 1, "maximum": 253},
                "outOfGrid": "422 WEATHER_LOCATION_NOT_SUPPORTED",
            },
            {key: value for key, value in self.contract["gridPolicy"].items() if key != "selectorResolution"},
        )
        forecast = self.contract["forecastPolicy"]
        self.assertEqual("Asia/Seoul", forecast["timezone"])
        self.assertEqual("0 through 6 hours inclusive", forecast["ultraShortHorizon"])
        self.assertEqual("over 6 hours through 10 days inclusive", forecast["villageHorizon"])
        self.assertEqual("422 WEATHER_FORECAST_HORIZON_NOT_SUPPORTED", forecast["outsideHorizon"])
        self.assertEqual("VilageFcstInfoService_2.0", forecast["providerApiVersion"])
        self.assertEqual("2607", forecast["providerGuideVersion"])
        self.assertEqual("latest eligible base whose publication delay elapsed", forecast["baseSelection"])

    def test_storage_forecast_type_projection_is_explicit_and_matches_schema(self) -> None:
        self.assertEqual(
            {"ultra_short": "ultra_short", "short": "village"},
            self.contract["forecastPolicy"]["storageTypeToResponseType"],
        )
        schema = INITIAL_SCHEMA.read_text(encoding="utf-8")
        self.assertIn("forecast_type in ('ultra_short', 'short')", schema)
        rdb = RDB_SPEC.read_text(encoding="utf-8")
        self.assertIn("DB `short` → API `village`", rdb)

    def test_response_is_closed_and_category_fields_are_required_nullable(self) -> None:
        response = self.contract["schemas"]["WeatherForecastResponse"]
        self.assertIs(False, response["additionalProperties"])
        self.assertEqual(set(response["properties"]), set(response["required"]))
        category_fields = self.contract["categoryPolicy"]["responseFields"]
        self.assertEqual(
            {
                "temperatureC", "precipitationProbabilityPercent", "precipitationAmountMm",
                "precipitationType", "skyCode", "humidityPercent", "windSpeedMps",
            },
            set(category_fields),
        )
        for field in category_fields:
            self.assertIs(True, response["properties"][field]["nullable"])
        self.assertEqual("explicit null", self.contract["categoryPolicy"]["unavailable"])
        self.assertEqual("forbidden", self.contract["categoryPolicy"]["omitted"])
        self.assertEqual("not exposed", self.contract["categoryPolicy"]["rawCategory"])

    def test_freshness_fallback_and_exhaustion_are_exact(self) -> None:
        policy = self.contract["freshnessPolicy"]
        self.assertEqual("response assembly time", policy["evaluatedAt"])
        self.assertEqual("stale = evaluatedAt >= expiresAt", policy["staleRule"])
        self.assertEqual("exactly one immediately previous eligible base", policy["fallbackLimit"])
        self.assertEqual("fallbackUsed=true and stale=true", policy["fallbackSuccess"])
        self.assertEqual("503 WEATHER_FORECAST_UNAVAILABLE", policy["fallbackExhausted"])
        response = self.contract["schemas"]["WeatherForecastResponse"]
        for field in ("provider", "observedAt", "expiresAt", "stale", "fallbackUsed"):
            self.assertIn(field, response["required"])

    def test_auth_owner_security_and_no_cursor_are_exact(self) -> None:
        """위치 비수집 v2 계약과 오류·근거의 변경 경계를 검증한다."""
        endpoint = self.contract["endpoints"][0]
        self.assertEqual({"mode": "optional", "missingToken": "anonymous", "invalidToken": 401}, endpoint["auth"])
        self.assertEqual("regionCode/placeId: public explicit selection; tripItemId: authenticated canonical JWT sub owner only", endpoint["owner"])
        self.assertEqual({"type": "none"}, endpoint["pagination"])
        self.assertEqual({"required": False, "header": "none"}, endpoint["idempotency"])
        security = self.contract["securityPolicy"]
        self.assertEqual("canonical JWT sub only; tripItemId ownership required; never location-derived identity", security["principal"])
        self.assertIn("request precise coordinates", security["forbiddenPersistence"])
        self.assertIn("raw token", security["forbiddenLogging"])

    def test_problem_details_are_exact_eight_fields_and_korean(self) -> None:
        """위치 비수집 v2 계약과 오류·근거의 변경 경계를 검증한다."""
        expected_fields = {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"}
        problems = self.contract["errorConditions"]
        self.assertEqual(
            {"INVALID_WEATHER_SELECTOR", "AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN", "WEATHER_REFERENCE_NOT_FOUND", "WEATHER_LOCATION_NOT_SUPPORTED", "WEATHER_FORECAST_HORIZON_NOT_SUPPORTED", "WEATHER_FORECAST_UNAVAILABLE"},
            {item["code"] for item in problems},
        )
        for problem in problems:
            self.assertEqual(expected_fields, set(problem["example"]))
            self.assertEqual(problem["status"], problem["example"]["status"])
            self.assertEqual(problem["code"], problem["example"]["code"])
            self.assertRegex(problem["example"]["title"], "[가-힣]")
            self.assertRegex(problem["example"]["detail"], "[가-힣]")

    def test_catalog_template_fixture_and_rdb_projection_are_present(self) -> None:
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        template = json.loads(TEMPLATE.read_text(encoding="utf-8"))
        endpoint = self.contract["endpoints"][0]
        catalog_endpoint = next(
            item for item in catalog["endpoints"]
            if (item["method"], item["path"]) == (endpoint["method"], endpoint["path"])
        )
        self.assertEqual(self.validator.catalog_projection(endpoint), catalog_endpoint)
        self.assertEqual(set(template["requiredEndpointFields"]), set(catalog_endpoint))
        self.assertEqual(template["templateId"], self.contract["inherits"])
        for name in ("request.json", "success.json", "problem.json"):
            self.assertTrue((FIXTURES / name).is_file())
        rdb = RDB_SPEC.read_text(encoding="utf-8")
        self.assertIn("contractVersion: `2.0.0`", rdb)
        self.assertIn("WEATHER_FORECAST_UNAVAILABLE", rdb)

    def test_v1_evidence_is_historical_and_v2_is_not_ready(self) -> None:
        """이전 계약 근거를 보존하되 v2 구현/외부 승인의 근거로 재사용하지 않는다."""
        import hashlib
        history = ROOT / self.contract["supersedes"]["contract"]
        self.assertEqual("60becfff2443526e541254009b64d436325168956fd9f9b8cb5409388b3cde6e", hashlib.sha256(history.read_bytes()).hexdigest())
        for source in ("notion", "figma"):
            self.assertEqual("not-linked", self.contract["externalTraceability"][source]["contractVersion"])
            self.assertIsNone(self.contract["externalTraceability"][source]["evidence"])
        self.assertEqual({stage: {"status": "not-ready", "evidence": None} for stage in ("metadata", "example", "implementation")}, self.contract["readiness"])

    def test_issue94_implementation_evidence_missing_wrong_or_tampered_path_fails(self) -> None:
        """위치 비수집 v2 계약과 오류·근거의 변경 경계를 검증한다."""
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        weather = next(item for item in catalog["domainContracts"] if item["issue"] == 94)
        evidence = {
            "controller": "services/spring-api/src/main/java/com/timingjeju/api/domain/weather/controller/WeatherForecastController.java",
            "controllerTest": "services/spring-api/src/test/java/com/timingjeju/api/domain/weather/controller/WeatherForecastControllerTest.java",
            "serviceTest": "services/spring-api/src/test/java/com/timingjeju/api/domain/weather/service/WeatherForecastQueryServiceTest.java",
            "repositoryTest": "services/spring-api/src/test/java/com/timingjeju/api/global/weather/JdbcWeatherForecastRepositoryIntegrationTest.java",
            "openApiTest": "services/spring-api/src/test/java/com/timingjeju/api/documentation/WeatherForecastOpenApiIntegrationTest.java",
            "contractTest": "scripts/tests/test_weather_forecast_contract.py",
        }
        weather["readiness"]["implementation"] = {
            "status": "ready",
            "evidence": evidence,
        }
        self.assertTrue(self.common_validator.validate_catalog(catalog))

        mutations = (
            ("missing", lambda value: value.pop("serviceTest")),
            ("wrong", lambda value: value.update(controllerTest=value["serviceTest"])),
            ("tampered", lambda value: value.update(contractTest="scripts/tests/test_contract_suite_integration.py")),
        )
        for label, mutate in mutations:
            with self.subTest(label=label):
                candidate = copy.deepcopy(catalog)
                candidate_weather = next(
                    item for item in candidate["domainContracts"] if item["issue"] == 94
                )
                mutate(candidate_weather["readiness"]["implementation"]["evidence"])
                errors = self.common_validator.validate_catalog(candidate)
                self.assertTrue(
                    any("Implementation Ready" in error for error in errors), errors
                )
        self.assertEqual(
            {
                "node": "1291:8816",
                "action": "1291:8819",
                "loading": "1291:8820",
                "empty": "1291:8822",
                "error": "1291:8823",
            },
            self.contract["endpoints"][0]["figma"],
        )

    def test_external_and_readiness_reject_paired_authoritative_lineage_mutation(self) -> None:
        """기존 공공 사실과 새 계약의 검증 불가 상태를 정확히 유지한다."""
        candidate = copy.deepcopy(self.contract)
        wrong_page_id = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        wrong_page_url = "https://app.notion.com/p/ffffffffffffffffffffffffffffffff"
        candidate["externalTraceability"]["notion"]["evidence"] = {"pageId": wrong_page_id, "pageUrl": wrong_page_url}
        candidate["readiness"]["metadata"]["evidence"] = {}
        candidate["readiness"]["metadata"]["evidence"]["notionPage"] = {
            "url": wrong_page_url,
            "pageId": wrong_page_id,
        }

        with tempfile.TemporaryDirectory() as temporary:
            candidate_path = Path(temporary) / "contract.json"
            candidate_path.write_text(
                json.dumps(candidate, ensure_ascii=False), encoding="utf-8"
            )
            with mock.patch.object(self.validator, "DEFAULT_CONTRACT", candidate_path):
                errors = self.validator.validate(candidate, skip_catalog_fixtures=True)

        self.assertTrue(any("external readiness" in error for error in errors), errors)

    def test_external_and_readiness_reject_figma_file_and_node_mismatch(self) -> None:
        """기존 공공 사실과 새 계약의 검증 불가 상태를 정확히 유지한다."""
        mutations = (
            {"fileKey": "WrongFileKey"},
            {
                "url": "https://www.figma.com/design/4mKep38zm17iupVSQVsSJW?node-id=1291-9999",
                "nodeId": "1291:9999",
            },
        )
        for updates in mutations:
            with self.subTest(updates=updates):
                candidate = copy.deepcopy(self.contract)
                candidate["readiness"]["metadata"]["evidence"] = {"figmaNode": updates}
                errors = self.validator.validate(candidate, skip_catalog_fixtures=True)
                self.assertTrue(
                    any("external readiness" in error for error in errors),
                    errors,
                )

    def test_validator_rejects_contract_drift(self) -> None:
        """기존 공공 사실과 새 계약의 검증 불가 상태를 정확히 유지한다."""
        mutations = (
            ("query", lambda value: value["schemas"]["WeatherForecastQuery"]["required"].remove("dateTime")),
            ("grid", lambda value: value["gridPolicy"].update(rounding="round")),
            ("horizon", lambda value: value["forecastPolicy"].update(villageHorizon="unbounded")),
            ("storage projection", lambda value: value["forecastPolicy"]["storageTypeToResponseType"].update(short="short")),
            ("category", lambda value: value["categoryPolicy"].update(omitted="allowed")),
            ("fallback", lambda value: value["freshnessPolicy"].update(fallbackLimit="unbounded")),
            ("problem", lambda value: value["errorConditions"][0]["example"].update(message="forbidden")),
            ("external readiness", lambda value: value["externalTraceability"]["notion"].update(status="drift-blocked")),
            ("external readiness", lambda value: value["externalTraceability"]["notion"].update(evidence={"pageId": "drift"})),
            ("external readiness", lambda value: value["externalTraceability"]["figma"].update(status="ready")),
            ("response schema", lambda value: value["schemas"]["WeatherForecastResponse"]["properties"]["providerApiVersion"].update(const="drift")),
            ("endpoint canonical", lambda value: value["endpoints"][0].update(dbOwner="drift")),
            ("schemaGap exact", lambda value: value["schemaGap"].__setitem__(0, "drift")),
        )
        for expected, mutate in mutations:
            with self.subTest(expected=expected):
                candidate = copy.deepcopy(self.contract)
                mutate(candidate)
                result = self._run_validator(candidate)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stdout + result.stderr)

    def test_fixture_semantics_are_validated_fail_closed(self) -> None:
        self.assertEqual([], self.validator.validate_fixtures(self.contract))

    def test_query_boundary_values_fail_schema_validation(self) -> None:
        """selector 누락·복수·GPS·UUID·시간대·미지 필드를 거부한다."""
        schema = self.contract["schemas"]["WeatherForecastQuery"]
        cases = (
            ({"dateTime": "2026-08-03T14:00:00+09:00"}, "oneOf"),
            ({"regionCode": "jeju-si", "placeId": "invalid", "dateTime": "2026-08-03T14:00:00+09:00"}, "oneOf"),
            ({"placeId": "invalid", "dateTime": "2026-08-03T14:00:00+09:00"}, "pattern"),
            ({"regionCode": "jeju-si", "lat": 33.4, "dateTime": "2026-08-03T14:00:00+09:00"}, "additionalProperties"),
            ({"regionCode": "jeju-si", "dateTime": "2026-08-03T05:00:00Z"}, "+09:00"),
            ({"regionCode": None, "dateTime": "2026-08-03T14:00:00+09:00"}, "nullable"),
        )
        for value, expected in cases:
            with self.subTest(expected=expected):
                errors = []
                self.validator._validate_value(value, schema, self.contract["schemas"], "query", errors)
                self.assertTrue(any(expected in error for error in errors), errors)

    def test_response_rejects_omitted_category_and_accepts_explicit_null(self) -> None:
        schema = self.contract["schemas"]["WeatherForecastResponse"]
        body = json.loads((FIXTURES / "success.json").read_text(encoding="utf-8"))["body"]
        nullable = copy.deepcopy(body)
        nullable["precipitationProbabilityPercent"] = None
        errors = []
        self.validator._validate_value(nullable, schema, self.contract["schemas"], "response", errors)
        self.assertEqual([], errors)
        omitted = copy.deepcopy(body)
        omitted.pop("precipitationProbabilityPercent")
        errors = []
        self.validator._validate_value(omitted, schema, self.contract["schemas"], "response", errors)
        self.assertTrue(any("required" in error for error in errors), errors)

    def test_fixture_mutations_fail_closed(self) -> None:
        """위치 비수집 v2 계약과 오류·근거의 변경 경계를 검증한다."""
        cases = (
            ("request.json", lambda value: value["query"].pop("regionCode"), "oneOf"),
            ("request.json", lambda value: value["query"].update(dateTime="2026-08-03T05:00:00Z"), "+09:00"),
            ("request.json", lambda value: value["headers"].update(Authorization="Basic dXNlcjpwYXNz"), "pattern"),
            ("request.json", lambda value: value["headers"].update({"X-Internal-Secret": "forbidden"}), "additionalProperties"),
            ("request.json", lambda value: value.update(unknown="forbidden"), "request fixture top-level exact"),
            ("success.json", lambda value: value["body"].pop("temperatureC"), "required"),
            ("success.json", lambda value: value["body"].update(rawCategory="TMP"), "additionalProperties"),
            ("success.json", lambda value: value["body"].update(fallbackUsed=True, stale=False), "stale/fallback"),
            ("success.json", lambda value: value["body"].update(validAt="2026-08-03T15:00:00+09:00"), "validAt"),
            ("success.json", lambda value: value.update(unknown="forbidden"), "success fixture top-level exact"),
            ("problem.json", lambda value: value["examples"]["INVALID_ACCESS_TOKEN"].update(message="forbidden"), "problem fixture"),
            ("problem.json", lambda value: value.update(unknown="forbidden"), "problem fixture top-level exact"),
        )
        for filename, mutate, expected in cases:
            with self.subTest(filename=filename, expected=expected), tempfile.TemporaryDirectory() as temporary:
                target = Path(temporary)
                for name in ("request.json", "success.json", "problem.json"):
                    shutil.copy2(FIXTURES / name, target / name)
                path = target / filename
                payload = json.loads(path.read_text(encoding="utf-8"))
                mutate(payload)
                path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
                with mock.patch.object(self.validator, "FIXTURES", target):
                    errors = self.validator.validate_fixtures(self.contract)
                self.assertTrue(any(expected in error for error in errors), errors)

    def _run_validator(self, candidate: dict) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "contract.json"
            path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")
            return subprocess.run(
                ["python3", str(VALIDATOR), "--contract", str(path), "--skip-catalog-fixtures"],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )


if __name__ == "__main__":
    unittest.main()
