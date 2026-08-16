import copy
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/contracts/domains/schedules/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
RDB_SPEC = ROOT / "docs/designs/timing-jeju-backend-rdb-api-spec.md"
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
        self.assertEqual(
            "existing version identity/content and child items/legs are never edited; only atomic draft-to-active and prior active-to-superseded status transitions are allowed",
            self.contract["versionPolicy"]["immutable"],
        )

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
        self.assertEqual({"TripPath", "ScheduleItemPath", "ScheduleQuery", "ReadHeaders", "MutationHeaders", "CreateItemRequest", "PatchItemRequest", "DeleteItemQuery", "ReorderDay", "ReorderRequest", "MoveItemRequest", "ScheduleVersion", "ItemProgress", "ScheduleItem", "ScheduleLeg", "ScheduleDay", "ScheduleResponse", "MutationResponse"}, set(schemas))
        for name, schema in schemas.items():
            with self.subTest(schema=name):
                self.assertEqual("object", schema["type"])
                self.assertFalse(schema["nullable"])
                self.assertFalse(schema["additionalProperties"])
                self.assertIsInstance(schema["required"], list)
                self.assertIsInstance(schema["properties"], dict)
        self.assertEqual(["expectedActiveScheduleVersionId", "dayNo", "sequenceNo", "itemType", "plannedStartAt", "stayMinutes"], schemas["CreateItemRequest"]["required"])
        self.assertEqual(False, schemas["CreateItemRequest"]["additionalProperties"])
        self.assertEqual(["expectedActiveScheduleVersionId"], schemas["PatchItemRequest"]["required"])
        self.assertEqual("omitted=unchanged; memo alone nullable", schemas["PatchItemRequest"]["presence"])
        self.assertEqual(["expectedActiveScheduleVersionId", "days"], schemas["ReorderRequest"]["required"])
        self.assertEqual(["expectedActiveScheduleVersionId", "targetDayNo", "targetSequenceNo", "plannedStartAt"], schemas["MoveItemRequest"]["required"])
        self.assertEqual(["tripId", "scheduleVersion", "days"], schemas["ScheduleResponse"]["required"])
        self.assertEqual(["tripId", "previousScheduleVersionId", "activeScheduleVersionId", "versionNo", "sourceType", "feasibilityStale", "changedItemIds", "etag", "updatedAt"], schemas["MutationResponse"]["required"])
        self.assertEqual(["place_visit", "meal", "accommodation", "arrival", "departure", "free_time", "custom"], schemas["ScheduleItem"]["properties"]["itemType"]["enum"])
        self.assertEqual(1, schemas["CreateItemRequest"]["properties"]["stayMinutes"]["minimum"])
        self.assertEqual(1440, schemas["CreateItemRequest"]["properties"]["stayMinutes"]["maximum"])
        self.assertFalse(schemas["CreateItemRequest"]["properties"]["title"]["nullable"])
        self.assertTrue(schemas["CreateItemRequest"]["properties"]["memo"]["nullable"])
        self.assertEqual("date-time", schemas["ScheduleLeg"]["properties"]["plannedDepartureAt"]["format"])
        self.assertEqual(["walk", "public_transit", "rental_car", "taxi"], schemas["ScheduleLeg"]["properties"]["transportMode"]["enum"])
        self.assertEqual(["planned", "active", "arrived", "completed", "skipped", "missed"], schemas["ItemProgress"]["properties"]["status"]["enum"])

        for endpoint in self.contract["endpoints"]:
            with self.subTest(endpoint=(endpoint["method"], endpoint["path"])):
                self.assertEqual({"path", "query", "headers", "body"}, set(endpoint["schemas"]))
                self.assertIn(endpoint["successSchema"], {"ScheduleResponse", "MutationResponse"})

    def test_six_endpoints_are_bidirectionally_projected_to_canonical_catalog(self) -> None:
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        fields = json.loads((ROOT / "docs/contracts/rest/endpoint-template.json").read_text(encoding="utf-8"))["requiredEndpointFields"]
        domain = [{key: endpoint[key] for key in fields} for endpoint in self.contract["endpoints"]]
        projected = [endpoint for endpoint in catalog["endpoints"] if (endpoint["method"], endpoint["path"]) in EXPECTED_ENDPOINTS]
        self.assertEqual(domain, projected)

    def test_validator_rejects_domain_or_catalog_projection_drift(self) -> None:
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        cases = (("domain", self.contract, catalog), ("catalog", self.contract, catalog))
        for side, contract, source_catalog in cases:
            with self.subTest(side=side), tempfile.TemporaryDirectory() as temporary:
                candidate = copy.deepcopy(contract)
                candidate_catalog = copy.deepcopy(source_catalog)
                if side == "domain":
                    candidate["endpoints"][0]["dataLineage"] = "drift"
                else:
                    schedule = next(e for e in candidate_catalog["endpoints"] if e["path"].endswith("/schedule"))
                    schedule["dataLineage"] = "drift"
                contract_path = Path(temporary) / "contract.json"
                catalog_path = Path(temporary) / "catalog.json"
                contract_path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")
                catalog_path.write_text(json.dumps(candidate_catalog, ensure_ascii=False), encoding="utf-8")
                result = subprocess.run(
                    ["python3", str(VALIDATOR), "--contract", str(contract_path), "--catalog", str(catalog_path)],
                    cwd=ROOT, text=True, capture_output=True, check=False,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("bidirectional", result.stdout + result.stderr)

    def test_adjacent_leg_derivation_is_deterministic_and_atomic(self) -> None:
        policy = self.contract["legDerivationPolicy"]
        self.assertEqual(["reuse-unchanged-active-leg", "stored-route-snapshot", "conservative-walk-fallback", "reject-422"], policy["sourcePriority"])
        self.assertEqual("none", policy["requestTimeCall"])
        self.assertEqual("expiresAt DESC, observedAt DESC, snapshotId ASC", policy["storedSnapshot"]["tieBreaker"])
        self.assertEqual("walk", policy["conservativeFallback"]["transportMode"])
        self.assertEqual("ceil(distanceMeters / 50), minimum 1", policy["conservativeFallback"]["walkMinutes"])
        self.assertEqual("walkMinutes + waitMinutes + rideMinutes + transferMinutes", policy["durationInvariant"])
        self.assertEqual("422 SCHEDULE_LEG_INCOMPLETE; rollback draft, prior active pointer unchanged", policy["stableFailure"])
        self.assertEqual(["add", "delete", "reorder", "move"], list(policy["operations"]))
        for operation in policy["operations"].values():
            self.assertTrue(operation["affectedPairs"])
            self.assertIn("oldItemIdToNewItemId", operation["identityMapping"])
        self.assertEqual("new UUID for every copied item in the new schedule version", policy["itemIdentityMapping"]["newIdentity"])
        self.assertEqual("command-scoped bijection oldItemIdToNewItemId", policy["itemIdentityMapping"]["mapping"])
        self.assertIn("new item IDs", policy["reuse"])

    def test_db_source_types_and_uuid_idempotency_key_are_exact(self) -> None:
        schemas = self.contract["schemas"]
        db_sources = ["initial", "user_edit", "ai_generation", "recovery", "live_recalculation"]
        self.assertEqual(db_sources, schemas["ScheduleVersion"]["properties"]["sourceType"]["enum"])
        fixture_sources = json.loads((ROOT / "fixtures/contracts/schedules/success.json").read_text(encoding="utf-8"))["sourceTypeFixtures"]
        self.assertEqual(db_sources, fixture_sources)
        key_schema = schemas["MutationHeaders"]["properties"]["Idempotency-Key"]
        self.assertEqual({"type": "string", "format": "uuid", "nullable": False}, key_schema)
        errors = []
        self.validator._validate_schema_value("not-a-uuid", key_schema, schemas, "Idempotency-Key", errors)
        self.assertTrue(any("UUID" in error for error in errors))

    def test_every_problem_has_exact_condition_and_owner_hidden_reference_codes(self) -> None:
        conditions = {item["code"]: item for item in self.contract["errorConditions"]}
        self.assertIn("ACCOMMODATION_NOT_FOUND", conditions)
        self.assertIn("TRANSPORT_EVENT_NOT_FOUND", conditions)
        self.assertTrue(all(set(item) == {"status", "code", "condition", "type", "title", "detail", "fixture"} for item in conditions.values()))
        self.assertTrue(all(item["condition"] for item in conditions.values()))
        for code in ("ACCOMMODATION_NOT_FOUND", "TRANSPORT_EVENT_NOT_FOUND"):
            self.assertEqual(404, conditions[code]["status"])
            self.assertIn("missing, cross-owner or wrong-trip", conditions[code]["condition"])
        create_matrix = self.contract["endpoints"][1]["errorMatrix"]["404"]
        patch_matrix = self.contract["endpoints"][2]["errorMatrix"]["404"]
        self.assertTrue({"ACCOMMODATION_NOT_FOUND", "TRANSPORT_EVENT_NOT_FOUND"}.issubset(create_matrix))
        self.assertTrue({"ACCOMMODATION_NOT_FOUND", "TRANSPORT_EVENT_NOT_FOUND"}.issubset(patch_matrix))
        matrix_codes = set()
        for endpoint in self.contract["endpoints"]:
            for status, codes in endpoint["errorMatrix"].items():
                for code in codes:
                    matrix_codes.add(code)
                    self.assertEqual(int(status), conditions[code]["status"])
        self.assertEqual(set(conditions), matrix_codes)

    def test_examples_and_spec_share_closed_response_shape(self) -> None:
        fixtures = json.loads((ROOT / "fixtures/contracts/schedules/success.json").read_text(encoding="utf-8"))["examples"]
        read = fixtures["readActive"]["body"]
        self.assertIn("feasibilityStale", read["scheduleVersion"])
        self.assertEqual(set(self.contract["schemas"]["ScheduleItem"]["required"]), set(read["days"][0]["items"][0]))
        for name in ("createItem", "patchItem", "deleteItem", "reorder", "move"):
            self.assertIn("etag", fixtures[name]["body"])
        create = json.loads((ROOT / "fixtures/contracts/schedules/request.json").read_text(encoding="utf-8"))["examples"]["createItem"]["body"]
        self.assertNotIn("title", create)
        self.assertFalse(any(value is None for key, value in create.items() if key != "memo"))
        spec = RDB_SPEC.read_text(encoding="utf-8")
        self.assertIn('"feasibilityStale": false', spec)
        self.assertIn('"etag": "\\"trip-13\\""', spec)
        self.assertNotIn('"title": null', spec[spec.index("### 12.3"):])

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
            ("OpenAPI schema", lambda c: c["schemas"]["ScheduleLeg"]["properties"].pop("transportMode")),
            ("OpenAPI schema", lambda c: c["schemas"]["CreateItemRequest"]["properties"]["stayMinutes"].update(maximum=10000)),
            ("OpenAPI schema", lambda c: c["schemas"]["CreateItemRequest"]["properties"]["title"].update(nullable=True)),
            ("OpenAPI schema", lambda c: c["schemas"]["ScheduleItem"]["required"].remove("bufferAfterMinutes")),
            ("OpenAPI schema", lambda c: c["schemas"]["MutationResponse"]["required"].remove("etag")),
            ("endpoint schema binding", lambda c: c["endpoints"][1]["schemas"].update(body="none")),
            ("leg derivation", lambda c: c["legDerivationPolicy"].update(requestTimeCall="private MCP")),
            ("sourceType", lambda c: c["schemas"]["ScheduleVersion"]["properties"]["sourceType"]["enum"].append("bogus")),
            ("Idempotency-Key UUID", lambda c: c["schemas"]["MutationHeaders"]["properties"]["Idempotency-Key"].pop("format")),
            ("item identity mapping", lambda c: c["legDerivationPolicy"].pop("itemIdentityMapping")),
            ("error condition", lambda c: c["errorConditions"][0].update(condition="different but non-empty condition")),
            ("error condition", lambda c: c["errorConditions"][0].update(code="BOGUS")),
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
