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
CONTRACT = ROOT / "docs/contracts/domains/accommodations/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
FIXTURE_DIR = ROOT / "fixtures/contracts/accommodations"
VALIDATOR = ROOT / "scripts/validate_accommodations_contract.py"
STORE = ROOT / "services/spring-api/src/main/java/com/timingjeju/api/domain/accommodation/adapter/JdbcAccommodationStore.java"
MIGRATION = ROOT / "supabase/migrations/20260907000002_trip_accommodation_contract.sql"
REQUEST_BOUNDARY = ROOT / "services/spring-api/src/main/java/com/timingjeju/api/domain/accommodation/controller/AccommodationRequestBoundary.java"
SPEC = importlib.util.spec_from_file_location("validate_accommodations_contract", VALIDATOR)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

EXPECTED_ENDPOINTS = {
    ("POST", "/api/v1/trips/{tripId}/accommodations"),
    ("PATCH", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"),
    ("DELETE", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"),
}


class AccommodationsContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))

    def test_identity_endpoints_and_inheritance_are_exact(self) -> None:
        self.assertEqual("timing-jeju-accommodations-contract/v1", self.contract["schemaVersion"])
        self.assertEqual("1.0.0", self.contract["contractVersion"])
        self.assertEqual("timing-jeju-rest-contract/v1", self.contract["inherits"])
        self.assertEqual(87, self.contract["ownerIssue"])
        self.assertEqual(EXPECTED_ENDPOINTS, {(e["method"], e["path"]) for e in self.contract["endpoints"]})
        self.assertEqual(3, len(self.contract["endpoints"]))

    def test_lodging_place_lookup_is_closed_and_preserves_freshness_semantics(self) -> None:
        source = " ".join(STORE.read_text(encoding="utf-8").split())

        self.assertIn("content_type_id = '32'", source)
        self.assertIn("tombstoned_at is null", source)
        self.assertIn("source_deleted_at is null", source)
        self.assertIn("stale = false", source)
        self.assertIn("(stale_at is null or stale_at > now())", source)

    def test_accommodation_acl_is_client_closed_and_service_role_is_least_privilege(self) -> None:
        sql = " ".join(MIGRATION.read_text(encoding="utf-8").split()).lower()

        for role in ("anon", "authenticated"):
            for table in ("trip_accommodations", "accommodation_idempotency"):
                self.assertIn(f"revoke all on public.{table} from {role}", sql)
            self.assertIn(
                "revoke execute on function public.protect_accommodation_idempotency_snapshot() "
                f"from {role}",
                sql,
            )
        self.assertIn(
            "revoke all on function public.protect_accommodation_idempotency_snapshot() from public",
            sql,
        )
        for table in ("trip_accommodations", "accommodation_idempotency"):
            self.assertIn(f"revoke all on public.{table} from service_role", sql)
            self.assertIn(
                f"grant select,insert,update,delete on public.{table} to service_role",
                sql,
            )
            self.assertNotIn(f"grant all on public.{table} to service_role", sql)
            for privilege in ("truncate", "references", "trigger"):
                self.assertNotIn(
                    f"grant {privilege} on public.{table} to service_role",
                    sql,
                )

    def test_raw_absent_servlet_length_must_match_bounded_body_but_unknown_is_allowed(self) -> None:
        source = " ".join(REQUEST_BOUNDARY.read_text(encoding="utf-8").split())

        self.assertIn("servletLength < -1 || servletLength > MAX_BODY_BYTES", source)
        self.assertIn("servletLength >= 0 && servletLength != body.length", source)
        self.assertNotIn("servletLength == -1", source)

    def test_xor_timezone_coverage_gap_overlap_and_order_are_explicit(self) -> None:
        policy = self.contract["accommodationPolicy"]
        expected = {
            "identityXor": "exactly one of placeId/customName",
            "timezone": "Asia/Seoul",
            "checkInOutTime": "HH:mm local wall-clock in Asia/Seoul",
            "dateInterval": "[checkInDate, checkOutDate)",
            "dateOrder": "checkInDate < checkOutDate",
            "tripCoverage": "within [trip.startDate, trip.endDate]",
            "gapOrOverlap": "reject-422",
            "canonicalOrder": "checkInDate ASC, checkOutDate ASC, accommodationId ASC",
            "sequencePolicy": "renumber contiguous 1..N in the same transaction",
        }
        self.assertEqual(expected, policy)

    def test_create_and_patch_presence_schemas_are_closed(self) -> None:
        create = self.contract["schemas"]["CreateAccommodationRequest"]
        self.assertEqual(False, create["additionalProperties"])
        self.assertEqual(
            {"placeId", "customName", "checkInDate", "checkOutDate", "checkInTime", "checkOutTime"},
            set(create["required"]),
        )
        self.assertEqual(2, len(create["oneOf"]))
        patch = self.contract["schemas"]["PatchAccommodationRequest"]
        self.assertEqual(False, patch["additionalProperties"])
        self.assertEqual([], patch["required"])
        self.assertEqual(1, patch["minProperties"])
        self.assertEqual(
            "omitted=unchanged; explicit null allowed only for the losing identity field",
            self.contract["patchPolicy"]["presence"],
        )
        self.assertEqual(
            "result must preserve placeId/customName exact XOR",
            self.contract["patchPolicy"]["identityResult"],
        )

    def test_idempotency_concurrency_delete_and_active_schedule_are_exact(self) -> None:
        endpoints = {(e["method"], e["path"]): e for e in self.contract["endpoints"]}
        post = endpoints[("POST", "/api/v1/trips/{tripId}/accommodations")]
        self.assertEqual(
            {"required": True, "header": "Idempotency-Key", "scope": "canonical sub + method + path + tripId", "ttl": "24 hours", "replay": "same payload returns original 201 status, body and ETag", "payloadConflict": "409 IDEMPOTENCY_KEY_REUSED", "concurrentRequest": "wait for first transaction then replay"},
            post["idempotency"],
        )
        self.assertEqual(["Authorization", "Idempotency-Key", "If-Match"], post["headersSchema"]["required"])
        for identity, endpoint in endpoints.items():
            with self.subTest(identity=identity):
                self.assertEqual("strong trip aggregate ETag", endpoint["concurrency"])
                self.assertEqual("canonical JWT sub; cross-owner 404", endpoint["owner"])
                self.assertEqual({"mode": "required", "missingToken": 401, "invalidToken": 401}, endpoint["auth"])
        active = self.contract["activeSchedulePolicy"]
        self.assertEqual("actual change invalidates active schedule atomically", active["postPatch"])
        self.assertEqual("reject-422 while an active schedule exists", active["delete"])
        self.assertEqual("no-op preserves active schedule and ETag", active["canonicalNoOp"])
        self.assertEqual(
            "return 200 with unchanged ETag and no schedule mutation",
            self.contract["patchPolicy"]["canonicalNoOp"],
        )

    def test_endpoint_shapes_and_error_matrix_are_closed_and_complete(self) -> None:
        required = {
            "method", "path", "operation", "requestSchema", "headersSchema", "successSchema",
            "auth", "owner", "presence", "responses", "errorMatrix", "idempotency", "pagination",
            "concurrency", "dbOwner", "requestTimeCall", "dataLineage", "figma", "contractVersion",
        }
        conditions = {item["code"]: item for item in self.contract["errorConditions"]}
        expected_codes = {
            "INVALID_REQUEST", "AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN", "TRIP_NOT_FOUND",
            "ACCOMMODATION_NOT_FOUND", "PLACE_NOT_FOUND", "IDEMPOTENCY_KEY_REUSED",
            "TRIP_VERSION_CONFLICT", "ACCOMMODATION_CONCURRENT_CONFLICT",
            "ACCOMMODATION_DATE_GAP_OR_OVERLAP", "ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE",
        }
        self.assertEqual(expected_codes, set(conditions))
        for endpoint in self.contract["endpoints"]:
            self.assertEqual(required, set(endpoint))
            matrix = endpoint["errorMatrix"]
            self.assertEqual({str(code) for code in endpoint["responses"]["errors"]}, set(matrix))
            for status, codes in matrix.items():
                self.assertTrue(codes)
                self.assertTrue(all(conditions[code]["status"] == int(status) for code in codes))

    def test_endpoint_schema_refs_schema_semantics_and_schema_gap_are_fail_closed(self) -> None:
        schema_mutations = (
            ("schema semantics", lambda c: c["schemas"]["Accommodation"]["properties"]["placeId"].update(format="date")),
            ("schemaGap", lambda c: c.update(schemaGap=[])),
        )
        for expected, mutate in schema_mutations:
            with self.subTest(expected=expected):
                candidate = copy.deepcopy(self.contract)
                mutate(candidate)
                result = self._run_contract(candidate)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stdout + result.stderr)
        endpoint_mutations = (
            ("POST", "requestSchema", "BogusRequest", "endpoint schema refs"),
            ("POST", "headersSchema.schema", "BogusHeaders", "endpoint required headers"),
            ("POST", "headersSchema.required", [], "endpoint required headers"),
            ("POST", "successSchema", "BogusResponse", "endpoint schema refs"),
            ("PATCH", "requestSchema", "BogusRequest", "endpoint schema refs"),
            ("PATCH", "headersSchema.schema", "BogusHeaders", "endpoint required headers"),
            ("PATCH", "headersSchema.required", [], "endpoint required headers"),
            ("PATCH", "successSchema", "BogusResponse", "endpoint schema refs"),
            ("DELETE", "requestSchema", "BogusRequest", "endpoint schema refs"),
            ("DELETE", "headersSchema.schema", "BogusHeaders", "endpoint required headers"),
            ("DELETE", "headersSchema.required", [], "endpoint required headers"),
            ("DELETE", "successSchema", "BogusResponse", "endpoint schema refs"),
        )
        for method, field, value, expected in endpoint_mutations:
            with self.subTest(method=method, field=field):
                candidate = copy.deepcopy(self.contract)
                endpoint = next(item for item in candidate["endpoints"] if item["method"] == method)
                if field.startswith("headersSchema."):
                    endpoint["headersSchema"][field.removeprefix("headersSchema.")] = value
                else:
                    endpoint[field] = value
                result = self._run_contract(candidate)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stdout + result.stderr)

    def test_delete_figma_has_no_invented_ui_linkage(self) -> None:
        delete = next(endpoint for endpoint in self.contract["endpoints"] if endpoint["method"] == "DELETE")
        self.assertEqual(
            {
                "node": "not-observed",
                "action": "숙소 삭제 UI/action not-linked",
                "loading": "not-observed",
                "empty": "not-observed",
                "error": "not-observed",
            },
            delete["figma"],
        )

    def test_request_fixture_semantics_reject_xor_empty_patch_and_bodies(self) -> None:
        mutations = (
            ("POST identity XOR", lambda f: f["examples"]["create"]["body"].update(customName="성산 숙소")),
            ("POST identity XOR", lambda f: f["examples"]["create"]["body"].update(placeId=None, customName=None)),
            ("PATCH empty body", lambda f: f["examples"]["patch"].update(body={})),
            ("PATCH identity result XOR", lambda f: f["examples"]["patch"]["body"].update(placeId=None)),
            ("DELETE body forbidden", lambda f: f["examples"]["delete"].update(body={})),
            ("Idempotency-Key", lambda f: f["examples"]["create"]["headers"].pop("Idempotency-Key")),
            ("If-Match", lambda f: f["examples"]["patch"]["headers"].pop("If-Match")),
        )
        for expected, mutate in mutations:
            with self.subTest(expected=expected):
                self.assertTrue(any(expected in e for e in self._fixture_errors(request_mutator=mutate)))

    def test_recursive_success_fixture_and_problem_linkage_are_fail_closed(self) -> None:
        self.assertEqual([], self._fixture_errors())
        success_mutations = (
            ("추가 response field", lambda f: f["examples"]["create"]["body"].update(extra=True)),
            ("schema required", lambda f: f["examples"]["patch"]["body"].pop("scheduleEffect")),
            ("schema UUID format", lambda f: f["examples"]["create"]["body"].update(accommodationId="bad")),
            ("schema date format", lambda f: f["examples"]["create"]["body"]["accommodation"].update(checkInDate="2026/08/03")),
            ("schema time format", lambda f: f["examples"]["create"]["body"]["accommodation"].update(checkInTime="3pm")),
            ("schema date-time format", lambda f: f["examples"]["patch"]["body"].update(updatedAt="later")),
            ("schema enum", lambda f: f["examples"]["create"]["body"].update(scheduleEffect="stale")),
            ("schema nullable", lambda f: f["examples"]["create"]["body"]["accommodation"].update(name=None)),
            ("schema additionalProperties", lambda f: f["examples"]["create"]["body"]["accommodation"].update(extra=True)),
            ("schema offset", lambda f: f["examples"]["patch"]["body"].update(updatedAt="2026-08-03T09:16:00Z")),
        )
        for expected, mutate in success_mutations:
            with self.subTest(expected=expected):
                self.assertTrue(any(expected in e for e in self._fixture_errors(success_mutator=mutate)))
        problem_mutations = (
            ("condition→problem fixture", lambda f: f["examples"].pop("422_date_gap_or_overlap")),
            ("ACCOMMODATION_NOT_FOUND", lambda f: f["examples"]["404_accommodation_not_found"].update(detail="drift")),
            ("problem field exact", lambda f: f["examples"]["422_active_schedule"].update(message="forbidden")),
            ("problem instance", lambda f: f["examples"]["409_trip_version_conflict"].update(instance="/raw/path")),
        )
        for expected, mutate in problem_mutations:
            with self.subTest(expected=expected):
                self.assertTrue(any(expected in e for e in self._fixture_errors(problem_mutator=mutate)))

    def test_contract_mutations_and_non_json_constants_fail_cleanly(self) -> None:
        mutations = (
            ("top-level", lambda c: c.update(unexpected=True)),
            ("endpoint field", lambda c: c["endpoints"][0].update(unexpected=True)),
            ("duplicate", lambda c: c["endpoints"].append(copy.deepcopy(c["endpoints"][0]))),
            ("required", lambda c: c["schemas"]["CreateAccommodationRequest"]["required"].remove("checkOutDate")),
            ("PATCH null/omitted", lambda c: c["schemas"]["PatchAccommodationRequest"].pop("minProperties")),
            ("XOR", lambda c: c["schemas"]["CreateAccommodationRequest"].pop("oneOf")),
            ("CreateHeaders", lambda c: c["schemas"]["CreateHeaders"]["required"].remove("Idempotency-Key")),
            ("Accommodation nested", lambda c: c["schemas"]["Accommodation"]["required"].remove("sequenceNo")),
            ("AccommodationMutationResponse", lambda c: c["schemas"]["AccommodationMutationResponse"]["required"].remove("etag")),
            ("error matrix", lambda c: c["endpoints"][0]["errorMatrix"].pop("422")),
            ("problem canonical", lambda c: c["errorConditions"][0].update(title="drift")),
            ("Notion/local contract version", lambda c: c["externalTraceability"]["notion"].update(contractVersion="v1.1")),
            ("Notion/local", lambda c: c["externalTraceability"]["notion"]["rows"][0].update(pageId="00000000-0000-0000-0000-000000000000")),
            ("Figma", lambda c: c["externalTraceability"]["figma"].update(pageNodeId="0:0")),
            ("Figma", lambda c: c["externalTraceability"]["figma"]["observedNodes"][0].update(action="guessed")),
        )
        for expected, mutate in mutations:
            with self.subTest(expected=expected):
                candidate = copy.deepcopy(self.contract)
                mutate(candidate)
                result = self._run_contract(candidate)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stdout + result.stderr)
        for constant in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(constant=constant), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "contract.json"
                path.write_text('{"schemaVersion":' + constant + '}', encoding="utf-8")
                result = subprocess.run(
                    ["python3", str(VALIDATOR), "--contract", str(path), "--skip-catalog-fixtures"],
                    cwd=ROOT, text=True, capture_output=True, check=False,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("계약 JSON", result.stdout + result.stderr)
                self.assertNotIn("Traceback", result.stdout + result.stderr)
        malformed_documents = (
            '{"schemaVersion":"a","schemaVersion":"b"}',
            "[]",
        )
        for document in malformed_documents:
            with self.subTest(document=document), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "contract.json"
                path.write_text(document, encoding="utf-8")
                result = subprocess.run(
                    ["python3", str(VALIDATOR), "--contract", str(path), "--skip-catalog-fixtures"],
                    cwd=ROOT, text=True, capture_output=True, check=False,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("Traceback", result.stdout + result.stderr)

    def test_notion_figma_catalog_and_fixture_scope_are_exact_without_false_readiness(self) -> None:
        notion = self.contract["externalTraceability"]["notion"]
        self.assertEqual("1.0.0", notion["contractVersion"])
        self.assertEqual("Implementation Ready", notion["specStatus"])
        self.assertEqual(EXPECTED_ENDPOINTS, {(row["method"], row["path"]) for row in notion["rows"]})
        figma = self.contract["externalTraceability"]["figma"]
        self.assertEqual("not-linked", figma["contractVersion"])
        self.assertEqual(["loading", "empty", "error"], figma["missingStateEvidence"])
        self.assertEqual({"329:5165", "182:3248", "653:11512"}, {n["nodeId"] for n in figma["observedNodes"]})
        self.assertTrue(all(stage["status"] == "not-ready" for stage in self.contract["readiness"].values()))
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        self.assertTrue(EXPECTED_ENDPOINTS <= {(e["method"], e["path"]) for e in catalog["endpoints"]})
        request = json.loads((FIXTURE_DIR / "request.json").read_text(encoding="utf-8"))
        success = json.loads((FIXTURE_DIR / "success.json").read_text(encoding="utf-8"))
        self.assertEqual({"create", "patch", "delete"}, set(request["examples"]))
        self.assertEqual({"create", "patch"}, set(success["examples"]))

    def _run_contract(self, candidate):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "contract.json"
        path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")
        return subprocess.run(
            ["python3", str(VALIDATOR), "--contract", str(path), "--skip-catalog-fixtures"],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )

    def _fixture_errors(self, success_mutator=None, request_mutator=None, problem_mutator=None):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            for name in ("request.json", "success.json", "problem.json"):
                shutil.copy2(FIXTURE_DIR / name, target / name)
            for name, mutate in (("success.json", success_mutator), ("request.json", request_mutator), ("problem.json", problem_mutator)):
                if mutate is not None:
                    path = target / name
                    payload = json.loads(path.read_text(encoding="utf-8"))
                    mutate(payload)
                    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            errors = []
            with mock.patch.object(MODULE, "FIXTURES", target):
                MODULE._validate_fixtures(self.contract, errors)
            return errors


if __name__ == "__main__":
    unittest.main()
