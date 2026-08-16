import copy
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/contracts/domains/schedules/contract.json"
VALIDATOR = ROOT / "scripts/validate_schedules_contract.py"
EXPECTED_ENDPOINTS = {
    ("GET", "/api/v1/trips/{tripId}/schedule"),
    ("POST", "/api/v1/trips/{tripId}/schedule-items"),
    ("PATCH", "/api/v1/trips/{tripId}/schedule-items/{itemId}"),
    ("DELETE", "/api/v1/trips/{tripId}/schedule-items/{itemId}"),
    ("PUT", "/api/v1/trips/{tripId}/schedule-order"),
    ("POST", "/api/v1/trips/{tripId}/schedule-items/{itemId}/move"),
}


class SchedulesContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        spec = importlib.util.spec_from_file_location("validate_schedules_contract", VALIDATOR)
        assert spec and spec.loader
        cls.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.validator)

    def test_identity_scope_and_inheritance_are_exact(self) -> None:
        self.assertEqual("timing-jeju-schedules-contract/v1", self.contract["schemaVersion"])
        self.assertEqual("1.0.0", self.contract["contractVersion"])
        self.assertEqual("timing-jeju-rest-contract/v1", self.contract["inherits"])
        self.assertEqual(88, self.contract["ownerIssue"])
        self.assertEqual([49, 50, 51], self.contract["implementationIssues"])
        self.assertEqual(EXPECTED_ENDPOINTS, {(e["method"], e["path"]) for e in self.contract["endpoints"]})

    def test_read_is_read_only_and_projects_immutable_active_version(self) -> None:
        read = self.contract["endpoints"][0]
        self.assertEqual("active when versionId omitted; explicit version must belong to same owner/trip", read["versionSelector"])
        self.assertNotIn(409, read["responses"]["errors"])
        self.assertEqual("none", read["concurrency"])
        self.assertEqual("read-only; never changes active pointer, version, items, legs or progress", read["transaction"])
        self.assertEqual(["dayNo", "sequenceNo"], self.contract["readPolicy"]["stableOrder"])
        self.assertEqual("exactly one adjacent leg for every consecutive item pair; zero for fewer than two", self.contract["versionPolicy"]["legCompleteness"])

    def test_all_mutations_create_and_atomically_activate_a_new_version(self) -> None:
        for endpoint in self.contract["endpoints"][1:]:
            with self.subTest(endpoint=(endpoint["method"], endpoint["path"])):
                self.assertEqual(["Authorization", "Idempotency-Key", "If-Match"], endpoint["requiredHeaders"])
                self.assertEqual("required UUID selector", endpoint["expectedActiveScheduleVersionId"])
                self.assertEqual("new user_edit version; validate complete copy then CAS active pointer in one transaction", endpoint["transaction"])
                self.assertEqual("canonical JWT sub; cross-owner and wrong-trip resource 404", endpoint["owner"])
        self.assertEqual("body", self.contract["mutationPolicy"]["expectedVersionLocation"]["POST"])
        self.assertEqual("query", self.contract["mutationPolicy"]["expectedVersionLocation"]["DELETE"])
        self.assertEqual("body", self.contract["mutationPolicy"]["expectedVersionLocation"]["PATCH"])
        self.assertEqual("body", self.contract["mutationPolicy"]["expectedVersionLocation"]["PUT"])
        self.assertEqual("strong trip aggregate ETag plus expected active schedule version", self.contract["mutationPolicy"]["concurrency"])

    def test_item_type_required_fields_completed_item_and_manual_validation_are_closed(self) -> None:
        self.assertEqual(
            {
                "place_visit": ["placeId", "plannedStartAt", "stayMinutes"],
                "meal": ["title", "plannedStartAt", "stayMinutes"],
                "accommodation": ["accommodationId", "plannedStartAt", "stayMinutes"],
                "arrival": ["transportEventId", "plannedStartAt", "stayMinutes"],
                "departure": ["transportEventId", "plannedStartAt", "stayMinutes"],
                "free_time": ["title", "plannedStartAt", "stayMinutes"],
                "custom": ["title", "plannedStartAt", "stayMinutes"],
            },
            self.contract["itemPolicy"]["requiredByType"],
        )
        self.assertEqual("reject-422; completed item cannot be patched, deleted, reordered or moved", self.contract["itemPolicy"]["completedItem"])
        self.assertEqual("DB constraints and synchronous deterministic validator only", self.contract["mutationPolicy"]["validator"])
        self.assertEqual("never call MCP/AI; correction requires separate schedule revision run owned by #89", self.contract["mutationPolicy"]["aiCorrection"])

    def test_required_optional_null_omitted_and_response_fields_are_machine_readable(self) -> None:
        schemas = self.contract["schemas"]
        self.assertEqual(["expectedActiveScheduleVersionId", "dayNo", "sequenceNo", "itemType", "plannedStartAt", "stayMinutes"], schemas["CreateItemRequest"]["required"])
        self.assertEqual(False, schemas["CreateItemRequest"]["additionalProperties"])
        self.assertEqual(["expectedActiveScheduleVersionId"], schemas["PatchItemRequest"]["required"])
        self.assertEqual("omitted=unchanged; memo alone nullable", schemas["PatchItemRequest"]["presence"])
        self.assertEqual(["expectedActiveScheduleVersionId", "days"], schemas["ReorderRequest"]["required"])
        self.assertEqual(["expectedActiveScheduleVersionId", "targetDayNo", "targetSequenceNo", "plannedStartAt"], schemas["MoveItemRequest"]["required"])
        self.assertEqual(["tripId", "scheduleVersion", "days"], schemas["ScheduleResponse"]["required"])
        self.assertEqual(["tripId", "previousScheduleVersionId", "activeScheduleVersionId", "versionNo", "sourceType", "feasibilityStale", "changedItemIds", "etag", "updatedAt"], schemas["MutationResponse"]["required"])

    def test_reorder_and_move_boundaries_are_exact(self) -> None:
        self.assertEqual("each active item ID exactly once across all submitted days; no missing, duplicate, foreign or extra ID", self.contract["orderPolicy"]["permutation"])
        self.assertEqual("renumber each affected day contiguously from 1 and rebuild every adjacent leg", self.contract["orderPolicy"]["result"])
        self.assertEqual("target day belongs to trip; local date of plannedStartAt equals target day date", self.contract["movePolicy"]["dayBoundary"])
        self.assertEqual("remove from source, insert at targetSequenceNo, compact both days, rebuild affected legs", self.contract["movePolicy"]["result"])

    def test_error_problem_and_external_evidence_are_honest(self) -> None:
        conditions = {item["code"]: item for item in self.contract["errorConditions"]}
        self.assertEqual(404, conditions["TRIP_NOT_FOUND"]["status"])
        self.assertEqual(404, conditions["SCHEDULE_ITEM_NOT_FOUND"]["status"])
        self.assertEqual(409, conditions["ACTIVE_SCHEDULE_VERSION_CONFLICT"]["status"])
        self.assertEqual(409, conditions["TRIP_VERSION_CONFLICT"]["status"])
        self.assertEqual(422, conditions["SCHEDULE_ITEM_COMPLETED"]["status"])
        self.assertTrue(all(item["title"] and item["detail"] for item in conditions.values()))
        external = self.contract["externalTraceability"]
        self.assertEqual("not-linked", external["notion"]["contractVersion"])
        self.assertEqual("not-linked", external["figma"]["contractVersion"])
        self.assertTrue(all(value["status"] == "not-ready" for value in self.contract["readiness"].values()))

    def test_validator_rejects_contract_drift(self) -> None:
        mutations = (
            ("endpoint", lambda c: c["endpoints"].pop()),
            ("read-only", lambda c: c["endpoints"][0]["responses"]["errors"].append(409)),
            ("required headers", lambda c: c["endpoints"][1]["requiredHeaders"].remove("If-Match")),
            ("permutation", lambda c: c["orderPolicy"].update(permutation="subset allowed")),
            ("owner", lambda c: c["endpoints"][2].update(owner="403")),
            ("한국어 Problem", lambda c: c["errorConditions"][0].update(detail="")),
            ("Notion/Figma", lambda c: c["externalTraceability"]["figma"].update(contractVersion="1.0.0")),
        )
        for expected, mutate in mutations:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as temporary:
                candidate = copy.deepcopy(self.contract)
                mutate(candidate)
                path = Path(temporary) / "contract.json"
                path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")
                result = subprocess.run(
                    ["python3", str(VALIDATOR), "--contract", str(path), "--skip-catalog-fixtures"],
                    cwd=ROOT, text=True, capture_output=True, check=False,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stdout + result.stderr)

    def test_canonical_contract_fixtures_and_catalog_validate(self) -> None:
        self.assertEqual([], self.validator.validate())


if __name__ == "__main__":
    unittest.main()
