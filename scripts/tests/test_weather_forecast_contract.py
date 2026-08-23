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
        self.assertEqual("timing-jeju-weather-forecast-contract/v1", self.contract["schemaVersion"])
        self.assertEqual("1.0.0", self.contract["contractVersion"])
        self.assertEqual("1.0.0", self.contract["sourceSpecVersion"])
        self.assertEqual("timing-jeju-rest-contract/v1", self.contract["inherits"])
        self.assertEqual(94, self.contract["ownerIssue"])
        self.assertEqual([67], self.contract["implementationIssues"])
        self.assertEqual(
            {("GET", "/api/v1/weather/forecast")},
            {(item["method"], item["path"]) for item in self.contract["endpoints"]},
        )

    def test_query_requires_lat_lng_datetime_together_and_is_closed(self) -> None:
        query = self.contract["schemas"]["WeatherForecastQuery"]
        self.assertIs(False, query["additionalProperties"])
        self.assertEqual(["lat", "lng", "dateTime"], query["required"])
        self.assertEqual({"lat", "lng", "dateTime"}, set(query["properties"]))
        self.assertEqual((-90, 90), (query["properties"]["lat"]["exclusiveMinimum"], query["properties"]["lat"]["exclusiveMaximum"]))
        self.assertEqual((-180, 180), (query["properties"]["lng"]["minimum"], query["properties"]["lng"]["maximum"]))
        date_time = query["properties"]["dateTime"]
        self.assertEqual("date-time", date_time["format"])
        self.assertEqual("Asia/Seoul", date_time["timezone"])
        self.assertEqual("+09:00", date_time["requiredOffset"])
        self.assertEqual(0, date_time["seconds"])

    def test_grid_base_horizon_version_and_selection_are_closed(self) -> None:
        self.assertEqual(
            {
                "projection": "KMA DFS 5km Lambert conformal conic",
                "rounding": "floor(projectedCoordinate + 0.5)",
                "nx": {"minimum": 1, "maximum": 149},
                "ny": {"minimum": 1, "maximum": 253},
                "outOfGrid": "422 WEATHER_LOCATION_NOT_SUPPORTED",
            },
            self.contract["gridPolicy"],
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
        endpoint = self.contract["endpoints"][0]
        self.assertEqual({"mode": "optional", "missingToken": "anonymous", "invalidToken": 401}, endpoint["auth"])
        self.assertEqual("none; public weather fact has no user owner", endpoint["owner"])
        self.assertEqual({"type": "none"}, endpoint["pagination"])
        self.assertEqual({"required": False, "header": "none"}, endpoint["idempotency"])
        security = self.contract["securityPolicy"]
        self.assertEqual("canonical JWT sub only; not used for weather row selection", security["principal"])
        self.assertIn("request precise coordinates", security["forbiddenPersistence"])
        self.assertIn("raw token", security["forbiddenLogging"])

    def test_problem_details_are_exact_eight_fields_and_korean(self) -> None:
        expected_fields = {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"}
        problems = self.contract["errorConditions"]
        self.assertEqual(
            {"INVALID_WEATHER_FORECAST_QUERY", "INVALID_ACCESS_TOKEN", "WEATHER_LOCATION_NOT_SUPPORTED", "WEATHER_FORECAST_HORIZON_NOT_SUPPORTED", "WEATHER_FORECAST_UNAVAILABLE"},
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
        self.assertIn("contractVersion: `1.0.0`", rdb)
        self.assertIn("WEATHER_FORECAST_UNAVAILABLE", rdb)

    def test_external_and_actual_implementation_evidence_are_exact_and_ready(self) -> None:
        external = self.contract["externalTraceability"]
        notion = external["notion"]
        self.assertEqual(
            {
                "status": "ready",
                "contractVersion": "1.0.0",
                "evidence": {
                    "pageId": "3a40a87c-7ce5-816b-a8f7-ed2027e94b8c",
                    "pageUrl": "https://app.notion.com/p/3a40a87c7ce5816ba8f7ed2027e94b8c",
                    "method": "GET",
                    "path": "/api/v1/weather/forecast",
                    "specStatus": "Ready",
                    "auth": "Optional",
                    "screen": "장소 상세 / 일정 날씨 · Figma 1291:8816",
                    "db": ["weather_grid_points", "weather_forecasts"],
                    "alignedScope": ["response", "errors", "fallback", "security"],
                    "decisionComment": "https://github.com/Timing-Jeju/jeju_BE/issues/94#issuecomment-5387038123",
                },
                "ownerFollowUp": None,
            },
            notion,
        )
        figma = external["figma"]
        self.assertEqual(
            {
                "status": "ready",
                "contractVersion": "1.0.0",
                "evidence": {
                    "fileKey": "4mKep38zm17iupVSQVsSJW",
                    "contractNode": "1291:8816",
                    "actionNode": "1291:8819",
                    "loadingNode": "1291:8820",
                    "successNode": "1291:8821",
                    "emptyNode": "1291:8822",
                    "errorNode": "1291:8823",
                    "decisionComment": "https://github.com/Timing-Jeju/jeju_BE/issues/94#issuecomment-5387038123",
                },
                "ownerFollowUp": None,
            },
            figma,
        )
        self.assertEqual(
            {
                "metadata": {
                    "status": "ready",
                    "evidence": {
                        "localDocument": "docs/contracts/domains/weather-forecast/contract.md",
                        "notionPage": {
                            "url": "https://app.notion.com/p/3a40a87c7ce5816ba8f7ed2027e94b8c",
                            "pageId": "3a40a87c-7ce5-816b-a8f7-ed2027e94b8c",
                        },
                        "figmaNode": {
                            "url": "https://www.figma.com/design/4mKep38zm17iupVSQVsSJW?node-id=1291-8816",
                            "fileKey": "4mKep38zm17iupVSQVsSJW",
                            "nodeId": "1291:8816",
                        },
                    },
                },
                "example": {
                    "status": "ready",
                    "evidence": {
                        "requestFixture": "fixtures/contracts/weather-forecast/request.json",
                        "successFixture": "fixtures/contracts/weather-forecast/success.json",
                        "problemFixture": "fixtures/contracts/weather-forecast/problem.json",
                    },
                },
                "implementation": {
                    "status": "ready",
                    "evidence": {
                        "controller": "services/spring-api/src/main/java/com/timingjeju/api/domain/weather/controller/WeatherForecastController.java",
                        "controllerTest": "services/spring-api/src/test/java/com/timingjeju/api/domain/weather/controller/WeatherForecastControllerTest.java",
                        "serviceTest": "services/spring-api/src/test/java/com/timingjeju/api/domain/weather/service/WeatherForecastQueryServiceTest.java",
                        "repositoryTest": "services/spring-api/src/test/java/com/timingjeju/api/global/weather/JdbcWeatherForecastRepositoryIntegrationTest.java",
                        "openApiTest": "services/spring-api/src/test/java/com/timingjeju/api/documentation/WeatherForecastOpenApiIntegrationTest.java",
                        "contractTest": "scripts/tests/test_weather_forecast_contract.py",
                    },
                },
            },
            self.contract["readiness"],
        )

    def test_issue94_implementation_evidence_missing_wrong_or_tampered_path_fails(self) -> None:
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
        self.assertEqual([], self.common_validator.validate_catalog(catalog))

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
        candidate = copy.deepcopy(self.contract)
        wrong_page_id = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        wrong_page_url = "https://app.notion.com/p/ffffffffffffffffffffffffffffffff"
        candidate["externalTraceability"]["notion"]["evidence"].update(
            pageId=wrong_page_id,
            pageUrl=wrong_page_url,
        )
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

        self.assertTrue(any("authoritative lineage" in error for error in errors), errors)

    def test_external_and_readiness_reject_figma_file_and_node_mismatch(self) -> None:
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
                candidate["readiness"]["metadata"]["evidence"]["figmaNode"].update(
                    updates
                )
                errors = self.validator.validate(candidate, skip_catalog_fixtures=True)
                self.assertTrue(
                    any("Figma authoritative lineage" in error for error in errors),
                    errors,
                )

    def test_validator_rejects_contract_drift(self) -> None:
        mutations = (
            ("query", lambda value: value["schemas"]["WeatherForecastQuery"]["required"].remove("lng")),
            ("grid", lambda value: value["gridPolicy"].update(rounding="round")),
            ("horizon", lambda value: value["forecastPolicy"].update(villageHorizon="unbounded")),
            ("storage projection", lambda value: value["forecastPolicy"]["storageTypeToResponseType"].update(short="short")),
            ("category", lambda value: value["categoryPolicy"].update(omitted="allowed")),
            ("fallback", lambda value: value["freshnessPolicy"].update(fallbackLimit="unbounded")),
            ("problem", lambda value: value["errorConditions"][0]["example"].update(message="forbidden")),
            ("external readiness", lambda value: value["externalTraceability"]["notion"].update(status="drift-blocked")),
            ("external readiness", lambda value: value["externalTraceability"]["notion"]["evidence"].update(pageId="drift")),
            ("external readiness", lambda value: value["externalTraceability"]["figma"].update(status="not-ready")),
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
        schema = self.contract["schemas"]["WeatherForecastQuery"]
        cases = (
            ({"lat": 33.4, "dateTime": "2026-08-03T14:00:00+09:00"}, "required"),
            ({"lat": 90.0, "lng": 126.9, "dateTime": "2026-08-03T14:00:00+09:00"}, "exclusiveMaximum"),
            ({"lat": 33.4, "lng": -180.1, "dateTime": "2026-08-03T14:00:00+09:00"}, "minimum"),
            ({"lat": 33.4, "lng": 126.9, "dateTime": "2026-08-03T05:00:00Z"}, "+09:00"),
            ({"lat": 33.4, "lng": 126.9, "dateTime": "2026-08-03T14:00:00+09:00", "cursor": "forbidden"}, "additionalProperties"),
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
        cases = (
            ("request.json", lambda value: value["query"].pop("lat"), "required"),
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
