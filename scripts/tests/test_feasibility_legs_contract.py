import copy
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/contracts/domains/feasibility-legs/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
VALIDATOR = ROOT / "scripts/validate_feasibility_legs_contract.py"
FIXTURES = {
    "request": ROOT / "fixtures/contracts/feasibility-legs/request.json",
    "success": ROOT / "fixtures/contracts/feasibility-legs/success.json",
    "problem": ROOT / "fixtures/contracts/feasibility-legs/problem.json",
}
EXPECTED_ENDPOINT_OWNERS = {
    ("POST", "/api/v1/trips/{tripId}/feasibility-runs"): 55,
    ("GET", "/api/v1/trips/{tripId}/feasibility-runs/{runId}"): 97,
    (
        "GET",
        "/api/v1/trips/{tripId}/schedule-versions/{versionId}/legs/{legId}",
    ): 56,
}


class FeasibilityLegsContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        spec = importlib.util.spec_from_file_location("validate_feasibility_legs_contract", VALIDATOR)
        assert spec and spec.loader
        cls.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.validator)

    def _contract(self) -> dict:
        self.assertTrue(CONTRACT.is_file(), f"missing contract: {CONTRACT}")
        return json.loads(CONTRACT.read_text(encoding="utf-8"))

    def test_contract_validator_and_three_fixture_artifacts_exist(self) -> None:
        expected = [CONTRACT, VALIDATOR, *FIXTURES.values()]
        missing = [str(path.relative_to(ROOT)) for path in expected if not path.is_file()]
        self.assertEqual([], missing)

    def test_identity_inheritance_and_implementation_owners_are_exact(self) -> None:
        contract = self._contract()
        self.assertEqual("timing-jeju-feasibility-legs-contract/v1", contract["schemaVersion"])
        self.assertEqual("1.0.0", contract["contractVersion"])
        self.assertEqual("timing-jeju-rest-contract/v1", contract["inherits"])
        self.assertEqual(90, contract["ownerIssue"])
        self.assertEqual([55, 97, 56], contract["implementationIssues"])
        actual = {
            (endpoint["method"], endpoint["path"]): endpoint["ownerIssue"]
            for endpoint in contract["endpoints"]
        }
        self.assertEqual(EXPECTED_ENDPOINT_OWNERS, actual)

    def test_all_endpoints_require_canonical_sub_and_hide_cross_owner_resources(self) -> None:
        for endpoint in self._contract()["endpoints"]:
            with self.subTest(endpoint=(endpoint["method"], endpoint["path"])):
                self.assertEqual(
                    {"mode": "required", "missingToken": 401, "invalidToken": 401},
                    endpoint["auth"],
                )
                self.assertEqual("canonical JWT sub", endpoint["owner"])
                self.assertEqual(404, endpoint["crossOwnerStatus"])

    def test_intake_idempotency_and_run_state_projection_are_closed(self) -> None:
        contract = self._contract()
        intake = contract["endpoints"][0]
        self.assertEqual("Idempotency-Key", intake["idempotency"]["header"])
        self.assertTrue(intake["idempotency"]["required"])
        self.assertIn("409", intake["idempotency"]["payloadConflict"])
        self.assertEqual(
            ["queued", "running", "succeeded", "failed", "cancelled"],
            contract["runPolicy"]["statuses"],
        )
        self.assertEqual(
            "state-dependent required/null/omitted fields are closed by schemas and fixtures",
            contract["runPolicy"]["presence"],
        )

    def test_provenance_and_snapshot_freshness_are_machine_readable(self) -> None:
        contract = self._contract()
        self.assertEqual(
            ["algorithmVersion", "contractVersion", "commandInputHash", "mcpInputHash", "confidence"],
            contract["provenancePolicy"]["required"],
        )
        self.assertEqual(
            ["provider", "observedAt", "expiresAt", "stale"],
            contract["freshnessPolicy"]["requiredPerSnapshot"],
        )
        self.assertEqual("field", contract["freshnessPolicy"]["staleResultRepresentation"])
        self.assertEqual("never 409 solely because stale=true", contract["freshnessPolicy"]["staleRead"])

    def test_leg_time_components_and_totals_have_exact_sum_invariants(self) -> None:
        duration = self._contract()["durationPolicy"]
        self.assertEqual(
            ["walkMinutes", "waitMinutes", "rideMinutes", "transferMinutes"],
            duration["components"],
        )
        self.assertEqual("sum(components)", duration["totalMinutes"])
        self.assertEqual("arrivalAt - departureAt", duration["elapsedMinutes"])
        self.assertTrue(duration["requireTotalsToMatch"])

    def test_errors_nullability_and_raw_data_boundaries_are_closed(self) -> None:
        contract = self._contract()
        matrix = {entry["code"]: entry["status"] for entry in contract["errorConditions"]}
        self.assertEqual(
            {400, 401, 404, 409, 422, 429, 503},
            set(matrix.values()),
        )
        self.assertEqual(
            "every field is required, optional, nullable, or omitted by state; additional properties rejected",
            contract["presencePolicy"],
        )
        self.assertEqual(
            ["rawProviderPayload", "providerMessage", "token", "email", "userMetadata"],
            contract["securityPolicy"]["forbiddenResponseFields"],
        )

    def test_readiness_and_external_linkage_remain_fail_closed(self) -> None:
        contract = self._contract()
        self.assertEqual(
            {
                "metadata": {"status": "not-ready", "evidence": None},
                "example": {"status": "not-ready", "evidence": None},
                "implementation": {"status": "not-ready", "evidence": None},
            },
            contract["readiness"],
        )
        self.assertEqual(
            {
                "notion": {"status": "not-linked", "url": None},
                "figma": {"status": "not-linked", "url": None},
            },
            contract["externalTraceability"],
        )

    def test_catalog_is_an_exact_projection_of_all_three_endpoints(self) -> None:
        contract = self._contract()
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        fields = json.loads(
            (ROOT / "docs/contracts/rest/endpoint-template.json").read_text(encoding="utf-8")
        )["requiredEndpointFields"]
        projected = [
            endpoint
            for endpoint in catalog["endpoints"]
            if (endpoint["method"], endpoint["path"]) in EXPECTED_ENDPOINT_OWNERS
        ]
        expected = [{key: endpoint[key] for key in fields} for endpoint in contract["endpoints"]]
        self.assertEqual(expected, projected)

    def test_schemas_are_executable_closed_recursive_object_schemas(self) -> None:
        schemas = self._contract()["schemas"]
        expected = {
            "TripPath", "FeasibilityRunPath", "ScheduleLegPath", "FeasibilityRunHeaders",
            "FeasibilityRunRequest", "FeasibilityRunAccepted", "Failure", "RunProvenance",
            "SnapshotFreshness", "FeasibilityResult", "FeasibilityRunResponse", "LegEndpoint",
            "TransferStop", "Risk", "TransitRoute", "CarRoute", "WalkRoute", "LegDetailResponse",
            "ProblemDetails",
        }
        self.assertEqual(expected, set(schemas))
        for name, schema in schemas.items():
            with self.subTest(schema=name):
                self.assertEqual("object", schema["type"])
                self.assertFalse(schema["nullable"])
                self.assertFalse(schema["additionalProperties"])
                self.assertIsInstance(schema["required"], list)
                self.assertIsInstance(schema["properties"], dict)

    def test_recursive_schema_validator_rejects_type_format_enum_range_and_shape_mutations(self) -> None:
        schemas = {
            "Child": {
                "type": "object", "nullable": False, "additionalProperties": False,
                "required": ["name"],
                "properties": {"name": {"type": "string", "nullable": False, "minLength": 1, "maxLength": 5}},
            }
        }
        schema = {
            "type": "object", "nullable": False, "additionalProperties": False,
            "required": ["id", "at", "flag", "count", "score", "mode", "child", "items"],
            "properties": {
                "id": {"type": "string", "nullable": False, "format": "uuid"},
                "at": {"type": "string", "nullable": False, "format": "date-time"},
                "flag": {"type": "boolean", "nullable": False},
                "count": {"type": "integer", "nullable": False, "minimum": 0, "maximum": 2},
                "score": {"type": "number", "nullable": False, "minimum": 0, "maximum": 1},
                "mode": {"type": "string", "nullable": False, "enum": ["walk", "car"]},
                "child": {"$ref": "Child", "nullable": False},
                "items": {"type": "array", "nullable": False, "minItems": 1, "items": {"type": "integer", "nullable": False}},
            },
        }
        valid = {
            "id": "50000000-0000-0000-0000-000000000001", "at": "2026-08-23T09:00:00+09:00",
            "flag": True, "count": 1, "score": 0.5, "mode": "walk", "child": {"name": "제주"}, "items": [1],
        }
        errors: list[str] = []
        self.validator._validate_schema_value(valid, schema, schemas, "root", errors)
        self.assertEqual([], errors)
        mutations = (
            ("wrong-type", lambda value: value.update(flag="true")),
            ("bad-uuid", lambda value: value.update(id="not-uuid")),
            ("bad-date-time", lambda value: value.update(at="2026-08-23")),
            ("bad-enum", lambda value: value.update(mode="plane")),
            ("range", lambda value: value.update(count=3)),
            ("missing", lambda value: value.pop("score")),
            ("extra", lambda value: value.update(extra=True)),
            ("nested-extra", lambda value: value["child"].update(extra=True)),
            ("empty-array", lambda value: value.update(items=[])),
        )
        for name, mutate in mutations:
            with self.subTest(name=name):
                candidate = copy.deepcopy(valid)
                mutate(candidate)
                errors = []
                self.validator._validate_schema_value(candidate, schema, schemas, "root", errors)
                self.assertTrue(errors)

    def test_async_202_headers_hashes_failure_and_state_presence_are_exact(self) -> None:
        contract = self._contract()
        intake = contract["endpoints"][0]
        self.assertEqual(
            {"Location": "required concrete poll URL", "Retry-After": "required integer seconds 1..60"},
            intake["successHeaders"],
        )
        self.assertEqual(
            ["algorithmVersion", "contractVersion", "commandInputHash", "mcpInputHash", "confidence"],
            contract["provenancePolicy"]["required"],
        )
        failure = contract["schemas"]["Failure"]
        self.assertEqual(["code", "detail", "retryable"], failure["required"])
        self.assertEqual({"code", "detail", "retryable"}, set(failure["properties"]))
        response = contract["schemas"]["FeasibilityRunResponse"]
        self.assertEqual(
            ["runId", "status", "tripId", "scheduleVersionId", "requestedAt", "responseTime", "startedAt", "factsSnapshotAt", "sourceDataVersion", "provenance", "result", "failure"],
            response["required"],
        )

    def test_five_complete_run_state_fixtures_match_response_schema(self) -> None:
        contract = self._contract()
        states = json.loads(FIXTURES["success"].read_text(encoding="utf-8"))["examples"]["runStates"]
        self.assertEqual(["queued", "running", "succeeded", "failed", "cancelled"], list(states))
        for status, response in states.items():
            with self.subTest(status=status):
                self.assertEqual(status, response["status"])
                errors: list[str] = []
                self.validator._validate_schema_value(
                    response, {"$ref": "FeasibilityRunResponse", "nullable": False},
                    contract["schemas"], f"runStates.{status}", errors,
                )
                self.validator._validate_run_state(response, f"runStates.{status}", errors)
                self.assertEqual([], errors)

    def test_transit_car_walk_fixtures_are_complete_discriminated_legs(self) -> None:
        contract = self._contract()
        modes = json.loads(FIXTURES["success"].read_text(encoding="utf-8"))["examples"]["legModes"]
        self.assertEqual(["public_transit", "car", "walk"], list(modes))
        for mode, leg in modes.items():
            with self.subTest(mode=mode):
                self.assertEqual(mode, leg["transportMode"])
                errors: list[str] = []
                self.validator._validate_schema_value(
                    leg, {"$ref": "LegDetailResponse", "nullable": False},
                    contract["schemas"], f"legModes.{mode}", errors,
                )
                self.validator._validate_leg_semantics(leg, f"legModes.{mode}", errors)
                self.assertEqual([], errors)

    def test_freshness_uses_response_time_and_treats_expiry_equality_as_stale(self) -> None:
        cases = (
            ({"provider": "TAGO", "observedAt": "2026-08-23T08:59:00+09:00", "expiresAt": "2026-08-23T09:00:00+09:00", "stale": True}, []),
            ({"provider": "TMAP", "observedAt": "2026-08-23T08:59:00+09:00", "expiresAt": "2026-08-23T09:00:00.000001+09:00", "stale": False}, []),
            ({"provider": "TAGO", "observedAt": "2026-08-23T09:00:01+09:00", "expiresAt": "2026-08-23T09:00:00+09:00", "stale": True}, ["error"]),
            ({"provider": "TMAP", "observedAt": "2026-08-23T08:59:00+09:00", "expiresAt": "2026-08-23T09:00:00+09:00", "stale": False}, ["error"]),
        )
        for snapshot, expected in cases:
            with self.subTest(snapshot=snapshot):
                errors: list[str] = []
                self.validator._validate_freshness(snapshot, "2026-08-23T09:00:00+09:00", "snapshot", errors)
                self.assertEqual(bool(expected), bool(errors))

    def test_cli_rejects_malformed_or_wrong_root_without_traceback(self) -> None:
        candidates = ("[1]", '"text"', "1", '{"schemaVersion":NaN}', '{"schemaVersion":1,"schemaVersion":2}', "{")
        for raw in candidates:
            with self.subTest(raw=raw), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "contract.json"
                path.write_text(raw, encoding="utf-8")
                result = subprocess.run(
                    ["python3", str(VALIDATOR), "--contract", str(path)], cwd=ROOT,
                    text=True, capture_output=True, check=False,
                )
                output = result.stdout + result.stderr
                self.assertEqual(1, result.returncode)
                self.assertIn("[feasibility-legs contract]", output)
                self.assertNotIn("Traceback", output)

    def test_dedicated_validator_recursively_rejects_real_fixture_mutations(self) -> None:
        contract = self._contract()
        original = {
            name: json.loads(path.read_text(encoding="utf-8"))
            for name, path in FIXTURES.items()
        }
        mutations = (
            ("wrong-type", lambda value: value["examples"]["legModes"]["walk"].update(walkMinutes="20")),
            ("bad-format", lambda value: value["examples"]["runStates"]["queued"].update(runId="not-uuid")),
            ("bad-enum", lambda value: value["examples"]["legModes"]["car"].update(transportMode="plane")),
            ("out-of-range", lambda value: value["examples"]["runStates"]["succeeded"]["result"].update(totalScore=101)),
            ("missing", lambda value: value["examples"]["runStates"]["failed"]["failure"].pop("retryable")),
            ("extra", lambda value: value["examples"]["legModes"]["car"]["route"].update(raw="forbidden")),
            ("nested-shape", lambda value: value["examples"]["legModes"]["public_transit"].update(route=[])),
        )
        for name, mutate in mutations:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                candidate = copy.deepcopy(original)
                mutate(candidate["success"])
                fixture_dir = Path(temporary)
                for fixture_name, payload in candidate.items():
                    (fixture_dir / f"{fixture_name}.json").write_text(
                        json.dumps(payload, ensure_ascii=False), encoding="utf-8"
                    )
                errors: list[str] = []
                self.validator._validate_fixtures(contract, fixture_dir, errors)
                self.assertTrue(errors)

    def test_schema_meta_contract_rejects_recursive_definition_loosening(self) -> None:
        schemas = self._contract()["schemas"]
        mutations = (
            ("required", lambda value: value["TripPath"].pop("required")),
            ("properties", lambda value: value["TripPath"].pop("properties")),
            ("type", lambda value: value["TripPath"].pop("type")),
            ("format", lambda value: value["TripPath"]["properties"]["tripId"].pop("format")),
            ("pattern", lambda value: value["RunProvenance"]["properties"]["commandInputHash"].pop("pattern")),
            ("enum", lambda value: value["Risk"]["properties"]["status"].pop("enum")),
            ("minimum", lambda value: value["FeasibilityResult"]["properties"]["totalScore"].pop("minimum")),
            ("maximum", lambda value: value["FeasibilityResult"]["properties"]["totalScore"].pop("maximum")),
            ("minLength", lambda value: value["Failure"]["properties"]["detail"].pop("minLength")),
            ("maxLength", lambda value: value["Failure"]["properties"]["detail"].pop("maxLength")),
            ("additionalProperties", lambda value: value["Failure"].update(additionalProperties=True)),
            ("ref", lambda value: value["FeasibilityRunResponse"]["properties"]["failure"].update(**{"$ref": "Missing"})),
            ("oneOf", lambda value: value["LegDetailResponse"]["properties"]["route"].pop("oneOf")),
        )
        errors: list[str] = []
        self.validator._validate_schema_definitions(schemas, errors)
        self.assertEqual([], errors)
        for name, mutate in mutations:
            with self.subTest(name=name):
                candidate = copy.deepcopy(schemas)
                mutate(candidate)
                errors = []
                self.validator._validate_schema_definitions(candidate, errors)
                self.assertTrue(errors)

    def test_problem_fixture_is_executable_schema_validated_and_mutation_closed(self) -> None:
        contract = self._contract()
        problem = json.loads(FIXTURES["problem"].read_text(encoding="utf-8"))
        mutations = (
            ("title-type", lambda value: value["examples"]["400_invalid_request"].update(title=7)),
            ("detail-length", lambda value: value["examples"]["400_invalid_request"].update(detail="")),
            ("trace-format", lambda value: value["examples"]["400_invalid_request"].update(traceId="trace")),
            ("field-errors-type", lambda value: value["examples"]["400_invalid_request"].update(fieldErrors={})),
            ("extra", lambda value: value["examples"]["400_invalid_request"].update(rawMessage="forbidden")),
        )
        for name, mutate in mutations:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                candidate = copy.deepcopy(problem)
                mutate(candidate)
                fixture_dir = Path(temporary)
                for fixture_name, source in FIXTURES.items():
                    payload = candidate if fixture_name == "problem" else json.loads(source.read_text(encoding="utf-8"))
                    (fixture_dir / f"{fixture_name}.json").write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
                errors: list[str] = []
                self.validator._validate_fixtures(contract, fixture_dir, errors)
                self.assertTrue(errors)

    def test_terminal_phase_variants_are_explicit_and_hybrid_states_are_rejected(self) -> None:
        success = json.loads(FIXTURES["success"].read_text(encoding="utf-8"))["examples"]
        variants = success["terminalVariants"]
        self.assertEqual(
            ["failedPreMcp", "cancelledPreMcp", "failedStarted", "cancelledStarted"],
            list(variants),
        )
        for name, response in variants.items():
            with self.subTest(name=name):
                errors: list[str] = []
                self.validator._validate_run_state(response, f"terminalVariants.{name}", errors)
                self.assertEqual([], errors)
        hybrid = copy.deepcopy(variants["failedPreMcp"])
        hybrid["startedAt"] = "2026-08-23T09:00:01+09:00"
        errors = []
        self.validator._validate_run_state(hybrid, "hybrid", errors)
        self.assertTrue(errors)

    def test_succeeded_result_source_expiry_and_response_time_freshness_are_exact(self) -> None:
        contract = self._contract()
        result_schema = contract["schemas"]["FeasibilityResult"]
        self.assertEqual(
            ["resultSource", "stale", "expiresAt", "totalScore", "overallStatus", "reasonCodes", "snapshots"],
            result_schema["required"],
        )
        succeeded = json.loads(FIXTURES["success"].read_text(encoding="utf-8"))["examples"]["runStates"]["succeeded"]
        self.assertEqual(succeeded["responseTime"], succeeded["result"]["expiresAt"])
        self.assertTrue(succeeded["result"]["stale"])
        errors: list[str] = []
        self.validator._validate_run_state(succeeded, "succeeded", errors)
        self.assertEqual([], errors)
        candidate = copy.deepcopy(succeeded)
        candidate["result"]["stale"] = False
        errors = []
        self.validator._validate_run_state(candidate, "succeeded", errors)
        self.assertTrue(errors)

    def test_transit_stop_identity_and_chronology_mutations_are_rejected(self) -> None:
        transit = json.loads(FIXTURES["success"].read_text(encoding="utf-8"))["examples"]["legModes"]["public_transit"]
        mutations = (
            ("departure-stop", lambda value: value["route"].update(departureStopId="30000000-0000-0000-0000-000000000009")),
            ("arrival-stop", lambda value: value["route"].update(arrivalStopId="30000000-0000-0000-0000-000000000009")),
            ("sequence", lambda value: value["transferStops"][0].update(sequence=2)),
            ("arrival-after-departure", lambda value: value["transferStops"][0].update(arrivalAt="2026-08-23T13:06:00+09:00")),
            ("outside-leg", lambda value: value["transferStops"][0].update(arrivalAt="2026-08-23T12:39:00+09:00")),
            ("reverse-order", lambda value: value["transferStops"].append({"sequence": 2, "stopId": "30000000-0000-0000-0000-000000000004", "stopName": "두 번째 환승", "arrivalAt": "2026-08-23T12:59:00+09:00", "departureAt": "2026-08-23T13:01:00+09:00"})),
        )
        for name, mutate in mutations:
            with self.subTest(name=name):
                candidate = copy.deepcopy(transit)
                mutate(candidate)
                errors: list[str] = []
                self.validator._validate_leg_semantics(candidate, "transit", errors)
                self.assertTrue(errors)

    def test_command_and_mcp_hashes_are_canonical_sha256_hex(self) -> None:
        pattern = "^sha256:[0-9a-f]{64}$"
        provenance = self._contract()["schemas"]["RunProvenance"]["properties"]
        self.assertEqual(pattern, provenance["commandInputHash"]["pattern"])
        self.assertEqual(pattern, provenance["mcpInputHash"]["pattern"])
        states = json.loads(FIXTURES["success"].read_text(encoding="utf-8"))["examples"]["runStates"]
        for response in states.values():
            self.assertRegex(response["provenance"]["commandInputHash"], pattern)
            if response["provenance"]["mcpInputHash"] is not None:
                self.assertRegex(response["provenance"]["mcpInputHash"], pattern)

    def test_canonical_schema_constraint_values_reject_widening_mutations(self) -> None:
        schemas = self._contract()["schemas"]
        errors: list[str] = []
        self.validator._validate_canonical_schema_constraints(schemas, errors)
        self.assertEqual([], errors)
        mutations = (
            ("minimum-widen", lambda value: value["FeasibilityResult"]["properties"]["totalScore"].update(minimum=-100)),
            ("enum-extra", lambda value: value["Risk"]["properties"]["status"]["enum"].append("unknown")),
            ("uuid-permissive-pattern", lambda value: value["TripPath"]["properties"]["tripId"].clear() or value["TripPath"]["properties"]["tripId"].update(type="string", nullable=False, pattern=".*")),
            ("max-length-widen", lambda value: value["Failure"]["properties"]["detail"].update(maxLength=5000)),
            ("transfer-sequence-minimum-widen", lambda value: value["TransferStop"]["properties"]["sequence"].update(minimum=0)),
            ("leg-mode-enum-extra", lambda value: value["LegDetailResponse"]["properties"]["transportMode"]["enum"].append("bicycle")),
            ("leg-time-maximum-widen", lambda value: value["LegDetailResponse"]["properties"]["totalMinutes"].update(maximum=10080)),
            ("problem-status-minimum-widen", lambda value: value["ProblemDetails"]["properties"]["status"].update(minimum=0)),
        )
        for name, mutate in mutations:
            with self.subTest(name=name):
                candidate = copy.deepcopy(schemas)
                mutate(candidate)
                errors = []
                self.validator._validate_canonical_schema_constraints(candidate, errors)
                self.assertTrue(errors)

    def test_accepted_location_poll_url_and_retry_after_are_canonically_related(self) -> None:
        request = json.loads(FIXTURES["request"].read_text(encoding="utf-8"))["examples"]["intake"]
        accepted = json.loads(FIXTURES["success"].read_text(encoding="utf-8"))["examples"]["accepted"]
        errors: list[str] = []
        self.validator._validate_accepted_fixture(accepted, request["path"], errors)
        self.assertEqual([], errors)
        mutations = (
            ("location", lambda value: value["headers"].update(Location="/api/v1/trips/other/feasibility-runs/other")),
            ("poll-url", lambda value: value["body"].update(pollUrl="/api/v1/trips/other/feasibility-runs/other")),
            ("run-id", lambda value: value["body"].update(runId="63000000-0000-0000-0000-000000000099")),
        )
        for name, mutate in mutations:
            with self.subTest(name=name):
                candidate = copy.deepcopy(accepted)
                mutate(candidate)
                errors = []
                self.validator._validate_accepted_fixture(candidate, request["path"], errors)
                self.assertTrue(errors)
        for retry_after in ("+1", "01", " 1", "1 ", "0", "61"):
            with self.subTest(retry_after=retry_after):
                candidate = copy.deepcopy(accepted)
                candidate["headers"]["Retry-After"] = retry_after
                errors = []
                self.validator._validate_accepted_fixture(candidate, request["path"], errors)
                self.assertTrue(errors)

    def test_endpoint_walk_and_transfer_minutes_preserve_segment_arithmetic(self) -> None:
        contract = self._contract()
        endpoint_schema = contract["schemas"]["LegEndpoint"]
        self.assertIn("walkMinutes", endpoint_schema["required"])
        self.assertEqual(
            {"type": "integer", "nullable": False, "minimum": 0, "maximum": 1440},
            endpoint_schema["properties"]["walkMinutes"],
        )
        transit = json.loads(FIXTURES["success"].read_text(encoding="utf-8"))["examples"]["legModes"]["public_transit"]
        self.assertEqual(10, transit["from"]["walkMinutes"])
        self.assertEqual(13, transit["to"]["walkMinutes"])
        self.assertEqual(23, transit["walkMinutes"])
        self.assertEqual(5, transit["transferMinutes"])
        mutations = (
            ("walk-sum", lambda value: value.update(walkMinutes=22)),
            ("transfer-sum", lambda value: value.update(transferMinutes=4)),
            ("duration-total", lambda value: value.update(totalMinutes=value["totalMinutes"] + 1)),
        )
        for name, mutate in mutations:
            with self.subTest(name=name):
                candidate = copy.deepcopy(transit)
                mutate(candidate)
                errors: list[str] = []
                self.validator._validate_leg_semantics(candidate, "transit", errors)
                self.assertTrue(errors)

    def test_error_conditions_are_exactly_mapped_to_problem_fixtures_and_endpoints(self) -> None:
        contract = self._contract()
        problem = json.loads(FIXTURES["problem"].read_text(encoding="utf-8"))["examples"]
        errors: list[str] = []
        self.validator._validate_error_contract(contract, problem, errors)
        self.assertEqual([], errors)
        mutations = (
            ("name", lambda value: value["errorConditions"][0].update(name="swapped")),
            ("code", lambda value: value["errorConditions"][0].update(code="WRONG")),
            ("status", lambda value: value["errorConditions"][0].update(status=401)),
            ("type", lambda value: value["errorConditions"][0].update(type="https://api.timing-jeju.com/problems/wrong")),
            ("fixture", lambda value: value["errorConditions"][0].update(fixture="401_authentication_required")),
            ("applicability", lambda value: value["errorConditions"][0].update(endpoints=[])),
            ("condition", lambda value: value["errorConditions"][0].update(condition="swapped")),
        )
        for name, mutate in mutations:
            with self.subTest(name=name):
                candidate = copy.deepcopy(contract)
                mutate(candidate)
                errors = []
                self.validator._validate_error_contract(candidate, problem, errors)
                self.assertTrue(errors)


if __name__ == "__main__":
    unittest.main()
