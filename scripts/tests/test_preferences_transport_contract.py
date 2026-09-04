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
CONTRACT = ROOT / "docs/contracts/domains/preferences-transport/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
REQUEST = ROOT / "fixtures/contracts/preferences-transport/request.json"
SUCCESS = ROOT / "fixtures/contracts/preferences-transport/success.json"
PROBLEM = ROOT / "fixtures/contracts/preferences-transport/problem.json"
VALIDATOR = ROOT / "scripts/validate_preferences_transport_contract.py"

VALIDATOR_SPEC = importlib.util.spec_from_file_location(
    "validate_preferences_transport_contract", VALIDATOR
)
assert VALIDATOR_SPEC is not None and VALIDATOR_SPEC.loader is not None
VALIDATOR_MODULE = importlib.util.module_from_spec(VALIDATOR_SPEC)
VALIDATOR_SPEC.loader.exec_module(VALIDATOR_MODULE)

EXPECTED_ENDPOINTS = {
    ("PUT", "/api/v1/trips/{tripId}/preferences"),
    ("PUT", "/api/v1/trips/{tripId}/place-preferences"),
    ("PUT", "/api/v1/trips/{tripId}/transport-event"),
    ("DELETE", "/api/v1/trips/{tripId}/transport-event"),
}


class PreferencesTransportContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))

    def test_identity_and_common_contract_are_exact(self) -> None:
        self.assertEqual("timing-jeju-preferences-transport-contract/v1", self.contract["schemaVersion"])
        self.assertEqual("1.0.0", self.contract["contractVersion"])
        self.assertEqual("timing-jeju-rest-contract/v1", self.contract["inherits"])
        self.assertEqual(86, self.contract["ownerIssue"])
        actual = {(item["method"], item["path"]) for item in self.contract["endpoints"]}
        self.assertEqual(EXPECTED_ENDPOINTS, actual)
        self.assertEqual(4, len(self.contract["endpoints"]))

    def test_trim_nfc_uses_only_the_six_ascii_whitespace_characters(self) -> None:
        trim = " \t\n\r\f\v"
        preserved = "\x01jeju-si\x08"

        self.assertEqual("제주시", VALIDATOR_MODULE._canonical_trim_nfc(trim + "제주시" + trim))
        self.assertEqual(preserved, VALIDATOR_MODULE._canonical_trim_nfc(preserved))

        schema = {"type": "string", "normalization": "trim+nfc"}
        errors: list[str] = []
        VALIDATOR_MODULE._validate_schema_value(preserved, schema, {}, "region", errors)
        self.assertEqual([], errors)
        array_schema = {"type": "array", "items": schema}
        VALIDATOR_MODULE._validate_schema_value(
            [preserved], array_schema, {}, "regions", errors
        )
        self.assertEqual([], errors)
        for value, candidate_schema, path in (
            ("jeju\x00-si", schema, "region"),
            (["jeju\x00-si"], array_schema, "regions"),
        ):
            errors = []
            VALIDATOR_MODULE._validate_schema_value(
                value, candidate_schema, {}, path, errors
            )
            self.assertTrue(any("U+0000" in error for error in errors), path)
        for whitespace in ("\t", "\n", "\r", "\f", "\v", " "):
            errors = []
            VALIDATOR_MODULE._validate_schema_value(
                whitespace + "jeju-si", schema, {}, "region", errors
            )
            self.assertTrue(any("normalization" in error for error in errors), whitespace)


    def test_preferences_is_full_replace_with_closed_enums_and_primary_rule(self) -> None:
        policy = self.contract["preferencePolicy"]
        self.assertEqual("full-replace", policy["writeMode"])
        self.assertEqual("reject", policy["omittedRequiredField"])
        self.assertEqual("reject", policy["explicitNull"])
        self.assertEqual("reject-422", policy["duplicateCategoryOrRegion"])
        self.assertEqual(["public_transit", "rental_car", "taxi"], policy["transportModes"]["enum"])
        self.assertEqual("unique", policy["transportModes"]["mode"])
        self.assertEqual("contiguous 1..N and unique", policy["transportModes"]["priority"])
        self.assertEqual("exactly one; primary priority=1", policy["transportModes"]["primary"])

    def test_place_preferences_fix_duplicates_day_bounds_and_priority_ties(self) -> None:
        policy = self.contract["placePreferencePolicy"]
        self.assertEqual("full-replace", policy["writeMode"])
        self.assertEqual(["must_visit", "avoid"], policy["typeEnum"])
        self.assertEqual("reject 422; a place cannot appear as both must_visit and avoid", policy["samePlaceConflict"])
        self.assertEqual("1..tripDayCount or null", policy["targetDayNo"])
        self.assertEqual("priority DESC, placeId ASC", policy["priorityTieBreak"])

    def test_transport_event_fixes_timezone_xor_and_date_rules(self) -> None:
        policy = self.contract["transportEventPolicy"]
        self.assertEqual(["arrival", "departure"], policy["eventTypeEnum"])
        self.assertEqual(["flight", "ferry"], policy["transportTypeEnum"])
        self.assertEqual("RFC3339 date-time with mandatory +09:00 offset", policy["scheduledAt"])
        self.assertEqual("Asia/Seoul", policy["timezone"])
        self.assertEqual("arrival=startDate; departure=endDate", policy["localDate"])
        self.assertEqual("exactly one of terminalPlaceId/customTerminalName", policy["terminalXor"])
        self.assertEqual("eventType query parameter required", policy["deleteSelector"])

    def test_schedule_effect_and_delete_signal_are_explicit(self) -> None:
        effect = self.contract["scheduleEffectPolicy"]
        self.assertEqual("superseded", effect["activeVersionTransition"])
        self.assertEqual("clear", effect["activeScheduleVersionId"])
        self.assertEqual("draft", effect["tripStatusAfterInvalidation"])
        self.assertEqual("invalidated", effect["changedWithActiveSchedule"]["scheduleEffect"])
        self.assertTrue(effect["changedWithActiveSchedule"]["regenerationRequired"])
        delete_endpoint = next(
            item for item in self.contract["endpoints"] if item["method"] == "DELETE"
        )
        self.assertEqual([200], delete_endpoint["responses"]["success"])
        self.assertEqual("TransportEventMutationResponse", delete_endpoint["successSchema"])

    def test_success_responses_use_ref_composition_and_closed_world(self) -> None:
        expected_child_required = {
            "PreferencesResponse": {"preferences"},
            "PlacePreferencesResponse": {"items"},
            "TransportEventMutationResponse": {"eventType", "deleted", "event"},
        }
        for name, child_required in expected_child_required.items():
            with self.subTest(schema=name):
                schema = self.contract["schemas"][name]
                self.assertEqual(False, schema["unevaluatedProperties"])
                self.assertEqual(2, len(schema["allOf"]))
                self.assertEqual({"$ref": "MutationResponse"}, schema["allOf"][0])
                self.assertEqual(child_required, set(schema["allOf"][1]["required"]))

    def test_validator_rejects_response_composition_required_mutations(self) -> None:
        mutations = (
            ("allOf", lambda c: c["schemas"]["PreferencesResponse"].pop("allOf")),
            ("$ref", lambda c: c["schemas"]["PlacePreferencesResponse"]["allOf"][0].pop("$ref")),
            ("closed-world", lambda c: c["schemas"]["PreferencesResponse"].pop("unevaluatedProperties")),
            ("공통 required", lambda c: c["schemas"]["MutationResponse"]["required"].remove("tripId")),
            ("고유 required", lambda c: c["schemas"]["TransportEventMutationResponse"]["allOf"][1]["required"].remove("event")),
        )
        for expected, mutate in mutations:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "contract.json"
                candidate = copy.deepcopy(self.contract)
                mutate(candidate)
                path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")
                result = subprocess.run(
                    ["python3", str(VALIDATOR), "--contract", str(path), "--skip-catalog-fixtures"],
                    cwd=ROOT, text=True, capture_output=True, check=False,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stdout + result.stderr)

    def test_success_fixtures_match_effective_response_schema_exactly(self) -> None:
        self.assertEqual([], self._fixture_errors())
        mutations = (
            ("추가 response field", lambda f: f["examples"]["preferences"]["body"].update(unexpected=True)),
            ("누락 response field", lambda f: f["examples"]["placePreferences"]["body"].pop("tripId")),
            ("누락 response field", lambda f: f["examples"]["putTransportEvent"]["body"].pop("event")),
        )
        for expected, mutate in mutations:
            with self.subTest(expected=expected):
                self.assertTrue(
                    any(expected in error for error in self._fixture_errors(mutate)),
                    f"{expected} mutation을 validator가 거부해야 합니다.",
                )

    def test_success_fixture_recursive_schema_rejects_scalar_format_enum_nullable_and_nested_mutations(self) -> None:
        mutations = (
            ("schema type", lambda f: f["examples"]["preferences"]["body"].update(tripId=86)),
            ("schema UUID format", lambda f: f["examples"]["preferences"]["body"].update(tripId="not-a-uuid")),
            ("schema date-time format", lambda f: f["examples"]["preferences"]["body"].update(updatedAt="yesterday")),
            ("schema enum", lambda f: f["examples"]["preferences"]["body"].update(scheduleEffect="bogus")),
            ("schema nullable", lambda f: f["examples"]["preferences"]["body"]["preferences"].update(arrivalRegionCode=None)),
            ("schema required", lambda f: f["examples"]["preferences"]["body"]["preferences"].pop("transportModes")),
            ("schema additionalProperties", lambda f: f["examples"]["preferences"]["body"]["preferences"]["transportModes"][0].update(bogus=True)),
            ("schema offset", lambda f: f["examples"]["putTransportEvent"]["body"]["event"].update(scheduledAt="2026-08-17T04:30:00Z")),
            ("schema additionalProperties", lambda f: f["examples"]["deleteTransportEvent"]["body"].update(event={"bogus": True})),
        )
        for expected, mutate in mutations:
            with self.subTest(expected=expected):
                self.assertTrue(
                    any(expected in error for error in self._fixture_errors(mutate)),
                    f"{expected} mutation을 recursive schema validator가 거부해야 합니다.",
                )

    def test_success_fixture_put_delete_event_and_deleted_semantics_are_exact(self) -> None:
        mutations = (
            ("PUT event non-null", lambda f: f["examples"]["putTransportEvent"]["body"].update(event=None)),
            ("PUT deleted=false", lambda f: f["examples"]["putTransportEvent"]["body"].update(deleted=True)),
            ("DELETE event null", lambda f: f["examples"]["deleteTransportEvent"]["body"].update(event=copy.deepcopy(f["examples"]["putTransportEvent"]["body"]["event"]))),
            ("DELETE deleted=true", lambda f: f["examples"]["deleteTransportEvent"]["body"].update(deleted=False)),
        )
        for expected, mutate in mutations:
            with self.subTest(expected=expected):
                self.assertTrue(
                    any(expected in error for error in self._fixture_errors(mutate)),
                    f"{expected} endpoint semantic mutation을 validator가 거부해야 합니다.",
                )

    def test_endpoint_contract_has_auth_presence_errors_owner_figma_and_version(self) -> None:
        required = {
            "method", "path", "operation", "requestSchema", "headersSchema",
            "successSchema", "auth", "owner", "presence", "responses", "errorMatrix",
            "idempotency", "pagination", "dbOwner", "requestTimeCall", "dataLineage",
            "figma", "contractVersion",
        }
        for endpoint in self.contract["endpoints"]:
            with self.subTest(endpoint=(endpoint["method"], endpoint["path"])):
                self.assertEqual(required, set(endpoint))
                self.assertEqual(
                    {"mode": "required", "missingToken": 401, "invalidToken": 401},
                    endpoint["auth"],
                )
                self.assertEqual("canonical JWT sub; cross-owner 404", endpoint["owner"])
                self.assertEqual("1.0.0", endpoint["contractVersion"])
                self.assertEqual({"required": False, "header": "none"}, endpoint["idempotency"])
                self.assertEqual({"type": "none"}, endpoint["pagination"])
                self.assertEqual({400, 401, 404, 409, 422}, set(endpoint["responses"]["errors"]))
                self.assertEqual({"node", "action", "loading", "empty", "error"}, set(endpoint["figma"]))

    def test_error_matrix_covers_every_declared_status_and_condition(self) -> None:
        required_codes = {
            "INVALID_REQUEST", "AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN",
            "TRIP_NOT_FOUND", "PLACE_NOT_FOUND", "TRANSPORT_EVENT_NOT_FOUND",
            "TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT",
            "PREFERENCE_CONSTRAINT_VIOLATION", "PLACE_PREFERENCE_CONSTRAINT_VIOLATION",
            "TRANSPORT_EVENT_CONSTRAINT_VIOLATION",
        }
        actual_codes = {entry["code"] for entry in self.contract["errorConditions"]}
        self.assertTrue(required_codes <= actual_codes)
        conditions = {entry["code"]: entry for entry in self.contract["errorConditions"]}
        for endpoint in self.contract["endpoints"]:
            matrix = endpoint["errorMatrix"]
            self.assertEqual({str(code) for code in endpoint["responses"]["errors"]}, set(matrix))
            self.assertTrue(all(matrix[str(code)] for code in endpoint["responses"]["errors"]))
            for status, codes in matrix.items():
                for code in codes:
                    self.assertEqual(int(status), conditions[code]["status"])

        matrix_by_endpoint = {
            (item["method"], item["path"]): item["errorMatrix"]
            for item in self.contract["endpoints"]
        }
        for identity in EXPECTED_ENDPOINTS - {("DELETE", "/api/v1/trips/{tripId}/transport-event")}:
            self.assertIn("PLACE_NOT_FOUND", matrix_by_endpoint[identity]["404"])
        self.assertIn(
            "TRANSPORT_EVENT_NOT_FOUND",
            matrix_by_endpoint[("DELETE", "/api/v1/trips/{tripId}/transport-event")]["404"],
        )

    def test_validator_rejects_error_condition_fixture_link_mutations(self) -> None:
        contract_mutations = (
            ("PLACE_NOT_FOUND", lambda c: c["errorConditions"].remove(next(e for e in c["errorConditions"] if e["code"] == "PLACE_NOT_FOUND"))),
            ("TRANSPORT_EVENT_NOT_FOUND", lambda c: c["endpoints"][3]["errorMatrix"]["404"].remove("TRANSPORT_EVENT_NOT_FOUND")),
            ("problem type", lambda c: next(e for e in c["errorConditions"] if e["code"] == "PLACE_NOT_FOUND").update(type="https://invalid.example/problem")),
            ("problem fixture", lambda c: next(e for e in c["errorConditions"] if e["code"] == "TRANSPORT_EVENT_NOT_FOUND").pop("fixture")),
        )
        for expected, mutate in contract_mutations:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "contract.json"
                candidate = copy.deepcopy(self.contract)
                mutate(candidate)
                path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")
                result = subprocess.run(
                    ["python3", str(VALIDATOR), "--contract", str(path), "--skip-catalog-fixtures"],
                    cwd=ROOT, text=True, capture_output=True, check=False,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stdout + result.stderr)

        problem_mutations = (
            ("PLACE_NOT_FOUND", lambda f: f["examples"].pop("404_place_not_found")),
            ("TRANSPORT_EVENT_NOT_FOUND", lambda f: f["examples"]["404_transport_event_not_found"].update(detail="drift")),
        )
        for expected, mutate in problem_mutations:
            with self.subTest(expected=expected):
                self.assertTrue(
                    any(expected in error for error in self._fixture_errors(problem_mutator=mutate)),
                    f"{expected} problem fixture mutation을 validator가 거부해야 합니다.",
                )

    def test_notion_rows_and_figma_observations_are_exact_without_false_readiness(self) -> None:
        notion = self.contract["externalTraceability"]["notion"]
        self.assertEqual("1.0.0", notion["contractVersion"])
        self.assertEqual("Implementation Ready", notion["specStatus"])
        self.assertEqual(EXPECTED_ENDPOINTS, {(row["method"], row["path"]) for row in notion["rows"]})
        self.assertEqual(4, len({row["pageId"] for row in notion["rows"]}))
        figma = self.contract["externalTraceability"]["figma"]
        self.assertEqual("not-linked", figma["contractVersion"])
        self.assertEqual("251:4347", figma["pageNodeId"])
        self.assertEqual(["loading", "empty", "error"], figma["missingStateEvidence"])
        self.assertEqual("not-ready", self.contract["readiness"]["metadata"]["status"])

    def test_fixtures_are_versioned_synthetic_and_complete(self) -> None:
        request = json.loads(REQUEST.read_text(encoding="utf-8"))
        success = json.loads(SUCCESS.read_text(encoding="utf-8"))
        problem = json.loads(PROBLEM.read_text(encoding="utf-8"))
        self.assertEqual("1.0.0", request["contractVersion"])
        self.assertEqual("1.0.0", success["contractVersion"])
        self.assertEqual("1.0.0", problem["contractVersion"])
        self.assertEqual({"preferences", "placePreferences", "putTransportEvent", "deleteTransportEvent"}, set(request["examples"]))
        self.assertEqual(set(request["examples"]), set(success["examples"]))
        self.assertTrue(all("traceId" in value for value in problem["examples"].values()))

    def test_validator_rejects_required_null_duplicate_and_external_drift(self) -> None:
        mutations = (
            ("required", lambda c: c["schemas"]["PreferencesRequest"]["required"].remove("transportModes")),
            ("null/omitted", lambda c: c["schemas"]["PlacePreferenceItem"]["properties"]["targetDayNo"].update(nullable=False)),
            ("duplicate", lambda c: c["endpoints"].append(dict(c["endpoints"][0]))),
            ("error matrix", lambda c: c["endpoints"][0]["errorMatrix"].pop("422")),
            ("Figma", lambda c: c["externalTraceability"]["figma"].update(pageNodeId="0:0")),
            ("Notion/local contract version", lambda c: c["externalTraceability"]["notion"].update(contractVersion="v1.1")),
        )
        for expected, mutate in mutations:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "contract.json"
                candidate = json.loads(json.dumps(self.contract))
                mutate(candidate)
                path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")
                result = subprocess.run(
                    ["python3", str(VALIDATOR), "--contract", str(path), "--skip-catalog-fixtures"],
                    cwd=ROOT, text=True, capture_output=True, check=False,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stdout + result.stderr)

    def _fixture_errors(self, success_mutator=None, problem_mutator=None) -> list[str]:
        with tempfile.TemporaryDirectory() as temporary:
            fixture_dir = Path(temporary)
            for source in (REQUEST, SUCCESS, PROBLEM):
                shutil.copy2(source, fixture_dir / source.name)
            if success_mutator is not None:
                success = json.loads((fixture_dir / "success.json").read_text(encoding="utf-8"))
                success_mutator(success)
                (fixture_dir / "success.json").write_text(
                    json.dumps(success, ensure_ascii=False), encoding="utf-8"
                )
            if problem_mutator is not None:
                problem = json.loads((fixture_dir / "problem.json").read_text(encoding="utf-8"))
                problem_mutator(problem)
                (fixture_dir / "problem.json").write_text(
                    json.dumps(problem, ensure_ascii=False), encoding="utf-8"
                )
            errors: list[str] = []
            with mock.patch.object(VALIDATOR_MODULE, "FIXTURES", fixture_dir):
                VALIDATOR_MODULE._validate_fixtures(self.contract, errors)
            return errors


if __name__ == "__main__":
    unittest.main()
