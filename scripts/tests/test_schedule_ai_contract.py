import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/contracts/domains/schedule-ai/contract.json"
VALIDATOR = ROOT / "scripts/validate_schedule_ai_contract.py"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
IDENTITIES = [
    ("POST", "/api/v1/trips/{tripId}/generation-runs"),
    ("GET", "/api/v1/trips/{tripId}/generation-runs/{runId}"),
    ("POST", "/api/v1/trips/{tripId}/generation-runs/{runId}/candidates/{candidateId}/apply"),
    ("POST", "/api/v1/trips/{tripId}/schedule-revision-runs"),
    ("GET", "/api/v1/trips/{tripId}/schedule-revision-runs/{runId}"),
    ("POST", "/api/v1/trips/{tripId}/schedule-revision-runs/{runId}/candidates/{candidateId}/apply"),
]
REQUIRED_SCHEMAS = {
    "TripPath", "AsyncRunPath", "AsyncCandidatePath", "NoQuery", "BodyForbidden",
    "AsyncCreateHeaders", "AsyncReadHeaders", "AsyncApplyHeaders",
    "GenerationRunRequest", "ScheduleRevisionRunRequest", "ApplyCandidateRequest",
    "AsyncRunAccepted", "AsyncFailure", "GenerationCandidate", "RevisionCandidate",
    "GenerationResult", "RevisionDiff", "RevisionResult", "GenerationRunStatus", "RevisionRunStatus",
    "ApplyCandidateResponse",
}


class ScheduleAiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))

    def validate(self, candidate, catalog=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "contract.json"
            path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")
            catalog_path = Path(directory) / "catalog.json"
            catalog_path.write_text(
                json.dumps(catalog if catalog is not None else json.loads(CATALOG.read_text()), ensure_ascii=False),
                encoding="utf-8",
            )
            return subprocess.run(
                ["python3", str(VALIDATOR), "--contract", str(path), "--catalog", str(catalog_path)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )

    def assert_mutation_rejected(self, mutate):
        candidate = copy.deepcopy(self.contract)
        mutate(candidate)
        result = self.validate(candidate)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_canonical_contract_passes(self):
        result = self.validate(self.contract)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_six_endpoint_identities_are_exact(self):
        self.assert_mutation_rejected(
            lambda contract: contract["endpoints"][0].update(path="/api/v1/trips/{tripId}/generation-run")
        )

    def test_lifecycle_and_candidate_expiry_are_exact(self):
        self.assert_mutation_rejected(
            lambda contract: contract["lifecyclePolicy"]["runStatuses"].append("expired")
        )

    def test_acceptance_and_poll_headers_are_exact(self):
        self.assert_mutation_rejected(
            lambda contract: contract["headerPolicy"].update(retryAfterSeconds=3)
        )

    def test_problem_matrix_is_exact(self):
        self.assert_mutation_rejected(
            lambda contract: contract["problemMatrix"]["409"].pop()
        )

    def test_owner_and_cross_owner_hiding_are_exact(self):
        self.assert_mutation_rejected(
            lambda contract: contract["securityPolicy"].update(crossOwner="403")
        )

    def test_db_discriminator_and_foreign_keys_are_exact(self):
        self.assert_mutation_rejected(
            lambda contract: contract["databasePolicy"]["runDiscriminator"].update(
                itineraryGeneration="generation"
            )
        )

    def test_poll_url_and_retention_are_exact(self):
        self.assert_mutation_rejected(
            lambda contract: contract["retentionPolicy"].update(runResult="P6D")
        )

    def test_idempotency_and_concurrency_are_exact(self):
        self.assert_mutation_rejected(
            lambda contract: contract["idempotencyPolicy"].update(ttl="PT12H")
        )

    def test_idempotency_key_is_printable_ascii_not_uuid(self):
        expected = {
            "type": "string",
            "minLength": 1,
            "maxLength": 128,
            "pattern": "^[ -~]+$",
            "nullable": False,
        }
        for schema_name in ["AsyncCreateHeaders", "AsyncApplyHeaders"]:
            with self.subTest(schema=schema_name):
                key = self.contract["schemas"][schema_name]["properties"]["Idempotency-Key"]
                self.assertEqual(expected, key)
                self.assertNotIn("format", key)

    def test_location_input_is_closed_to_direct_user_selections(self):
        policy = self.contract["locationInputPolicy"]
        self.assertEqual(
            ["regionCode", "placeId", "tripItemId"],
            policy["allowedUserSelectedReferences"],
        )
        self.assertEqual("reject", policy["unknownFields"])
        self.assertEqual("owned trip item only", policy["tripItemIdOwnership"])
        self.assertFalse(policy["receiveCurrentOrIndirectLocation"])
        self.assertEqual(
            [
                "accuracy", "altitude", "currentLocation", "currentPlaceId",
                "deviceLocation", "geohash", "gps", "gridX", "gridY", "heading",
                "latitude", "locationSupplied", "longitude", "speed",
            ],
            policy["forbiddenFields"],
        )

    def test_generation_snapshot_preserves_saved_day_window_without_invented_default(self):
        snapshot = self.contract["generationSnapshotPolicy"]
        self.assertEqual(
            {
                "source": "trip_days.activity_start_time + trip_days.activity_end_time",
                "snapshot": "immutable exact stored pair for targetDayId",
                "missing": "explicitly absent",
                "defaulting": "forbidden; absence is never persisted or reported as a user fact",
            },
            snapshot["dayActivityWindow"],
        )

    def test_transport_event_snapshot_matches_current_public_contract(self):
        self.assertEqual(
            {
                "source": "trip_transport_events",
                "slots": ["arrival", "departure"],
                "eventTypes": ["arrival", "departure"],
                "transportTypes": ["flight", "ferry"],
                "fields": [
                    "eventType", "transportType", "terminalPlaceId", "customTerminalName",
                    "scheduledAt", "transportNumber", "note",
                ],
                "terminalSelector": "exactly one of terminalPlaceId or customTerminalName",
                "scheduledAt": "RFC3339 date-time with mandatory +09:00 offset",
                "missingSlot": "null",
            },
            self.contract["generationSnapshotPolicy"]["transportEvents"],
        )

    def test_apply_endpoints_share_exact_ordered_first_match_precedence(self):
        expected = [
            "AUTHENTICATION_REQUIRED",
            "INVALID_ACCESS_TOKEN",
            "INVALID_PATH_PARAMETER",
            "INVALID_ASYNC_RUN_REQUEST",
            "IDEMPOTENCY_KEY_REQUIRED",
            "IDEMPOTENCY_KEY_INVALID",
            "IF_MATCH_REQUIRED",
            "IF_MATCH_INVALID",
            "TRIP_NOT_FOUND",
            "ASYNC_RUN_NOT_FOUND",
            "CANDIDATE_NOT_FOUND",
            "IDEMPOTENT_REPLAY",
            "IDEMPOTENCY_KEY_REUSED",
            "CANDIDATE_ALREADY_APPLIED",
            "CANDIDATE_NOT_APPLICABLE_RUN_STATUS",
            "CANDIDATE_EXPIRED",
            "ACTIVE_SCHEDULE_VERSION_CONFLICT",
            "CANDIDATE_STALE",
            "CANDIDATE_NOT_APPLICABLE_LINEAGE",
            "ASYNC_RUN_QUOTA_EXCEEDED",
            "ASYNC_RESULT_TEMPORARILY_UNAVAILABLE",
            "APPLY_SUCCESS",
        ]
        apply_endpoints = [
            endpoint for endpoint in self.contract["endpoints"] if endpoint["operation"] == "apply"
        ]
        self.assertEqual(2, len(apply_endpoints))
        for endpoint in apply_endpoints:
            with self.subTest(path=endpoint["path"]):
                self.assertEqual(expected, endpoint["firstMatchPrecedence"])

    def test_apply_overlap_resolves_to_one_deterministic_outcome(self):
        from scripts.validate_schedule_ai_contract import resolve_apply_overlap

        base = {
            "authenticated": True,
            "accessTokenValid": True,
            "requestValid": True,
            "tripVisible": True,
            "runVisibleAndLinked": True,
            "candidateVisibleAndLinked": True,
            "replayCompleted": False,
            "idempotencyConflict": False,
            "alreadyApplied": False,
            "runSucceeded": True,
            "candidateSelectable": True,
            "expired": False,
            "requestedVersion": "A",
            "lockedActiveVersion": "A",
            "candidateBaseVersion": "A",
            "candidateLineageApplicable": True,
            "quotaAvailable": True,
            "storageAvailable": True,
        }
        overlaps = [
            (
                {
                    "authenticated": False,
                    "pathValid": False,
                    "tripVisible": False,
                    "idempotencyConflict": True,
                },
                "AUTHENTICATION_REQUIRED",
            ),
            (
                {"pathValid": False, "tripVisible": False, "replayCompleted": True},
                "INVALID_PATH_PARAMETER",
            ),
            (
                {
                    "runVisibleAndLinked": False,
                    "replayCompleted": True,
                    "alreadyApplied": True,
                },
                "ASYNC_RUN_NOT_FOUND",
            ),
            (
                {"candidateVisibleAndLinked": False, "replayCompleted": True},
                "CANDIDATE_NOT_FOUND",
            ),
            (
                {
                    "alreadyApplied": True,
                    "runSucceeded": False,
                    "expired": True,
                    "lockedActiveVersion": "B",
                    "candidateLineageApplicable": False,
                },
                "CANDIDATE_ALREADY_APPLIED",
            ),
            (
                {"runSucceeded": False, "expired": True, "lockedActiveVersion": "B"},
                "CANDIDATE_NOT_APPLICABLE",
            ),
            (
                {"expired": True, "lockedActiveVersion": "B"},
                "CANDIDATE_EXPIRED",
            ),
            (
                {"lockedActiveVersion": "B", "candidateLineageApplicable": False},
                "ACTIVE_SCHEDULE_VERSION_CONFLICT",
            ),
            (
                {"requestedVersion": "B", "lockedActiveVersion": "B"},
                "CANDIDATE_STALE",
            ),
            (
                {"candidateLineageApplicable": False},
                "CANDIDATE_NOT_APPLICABLE",
            ),
            (
                {
                    "replayCompleted": True,
                    "alreadyApplied": True,
                    "expired": True,
                    "lockedActiveVersion": "B",
                },
                "IDEMPOTENT_REPLAY",
            ),
            (
                {
                    "idempotencyConflict": True,
                    "alreadyApplied": True,
                    "expired": True,
                    "lockedActiveVersion": "B",
                },
                "IDEMPOTENCY_KEY_REUSED",
            ),
        ]
        for patch, expected in overlaps:
            with self.subTest(patch=patch):
                outcome = resolve_apply_overlap({**base, **patch})
                self.assertEqual(expected, outcome)

    def test_apply_precedence_mutation_is_rejected(self):
        self.assert_mutation_rejected(
            lambda contract: contract["endpoints"][2]["firstMatchPrecedence"].reverse()
        )

    def test_command_and_mcp_hashes_cannot_collapse(self):
        self.assert_mutation_rejected(
            lambda contract: contract["hashPolicy"].update(mcpInputHash="commandInputHash")
        )

    def test_local_readiness_cannot_claim_unobserved_external_evidence(self):
        self.assert_mutation_rejected(
            lambda contract: contract["externalTraceability"]["notion"].update(status="ready")
        )

    def test_external_contract_versions_and_readiness_are_fail_closed(self):
        for system in ("notion", "figma"):
            with self.subTest(system=system):
                self.assertEqual(
                    {
                        "status": "not-linked",
                        "contractVersion": "not-linked",
                        "evidence": None,
                    },
                    {
                        key: self.contract["externalTraceability"][system][key]
                        for key in ("status", "contractVersion", "evidence")
                    },
                )

        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        domain = next(item for item in catalog["domainContracts"] if item["issue"] == 89)
        self.assertEqual(
            {"local": "1.0.0", "notion": "not-linked", "figma": "not-linked"},
            domain["versions"],
        )
        self.assertTrue(
            all(
                value == {"status": "not-ready", "evidence": None}
                for value in domain["readiness"].values()
            )
        )

    def test_required_auth_codes_match_common_contract_both_directions(self):
        self.assertEqual(["AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN"], self.contract["problemMatrix"]["401"])
        self.assertEqual(
            {"missingTokenCode": "AUTHENTICATION_REQUIRED", "invalidTokenCode": "INVALID_ACCESS_TOKEN"},
            self.contract["commonAlignment"]["authentication"],
        )

    def test_every_endpoint_has_closed_typed_path_query_header_body_schemas(self):
        self.assertEqual(REQUIRED_SCHEMAS, set(self.contract["schemas"]))
        for endpoint in self.contract["endpoints"]:
            self.assertEqual({"path", "query", "headers", "body"}, set(endpoint["schemas"]))
            for schema_name in endpoint["schemas"].values():
                self.assertIn(schema_name, REQUIRED_SCHEMAS)
        for schema in self.contract["schemas"].values():
            if schema.get("kind") == "forbidden":
                self.assertEqual({"kind", "accepts"}, set(schema))
                self.assertEqual([], schema["accepts"])
                continue
            self.assertFalse(schema["additionalProperties"])
            self.assertIsInstance(schema["required"], list)
            self.assertIsInstance(schema["properties"], dict)

    def test_run_results_candidates_apply_and_states_are_explicit(self):
        for name in ["GenerationCandidate", "RevisionCandidate", "GenerationResult", "RevisionResult", "ApplyCandidateResponse"]:
            self.assertTrue(self.contract["schemas"][name]["required"])
        self.assertEqual(["queued", "running", "succeeded", "failed", "cancelled"], list(self.contract["stateResponses"]))
        for name, state in self.contract["stateResponses"].items():
            expected = {"required", "nullable", "omitted", "retryAfter"}
            if name in {"running", "failed", "cancelled"}:
                expected.add("oneOf")
            self.assertEqual(expected, set(state))

    def test_readback_dtos_include_exact_provenance_and_apply_links(self):
        generation = self.contract["schemas"]["GenerationResult"]
        revision = self.contract["schemas"]["RevisionResult"]
        self.assertEqual(
            {"baseScheduleVersionId", "factsAsOf", "stale", "resultSource", "candidates"},
            set(generation["required"]),
        )
        self.assertEqual(generation["required"], revision["required"])
        for candidate_name in ["GenerationCandidate", "RevisionCandidate"]:
            candidate = self.contract["schemas"][candidate_name]
            self.assertIn("applyUrl", candidate["required"])
            self.assertEqual("uri-reference", candidate["properties"]["applyUrl"]["format"])
        revision_candidate = self.contract["schemas"]["RevisionCandidate"]
        self.assertIn("diff", revision_candidate["required"])
        self.assertIn("preservedFields", revision_candidate["required"])
        self.assertEqual({"$ref"}, set(revision_candidate["properties"]["diff"]))

    def test_failed_and_cancelled_are_closed_pre_mcp_or_post_start_oneof(self):
        variants = self.contract["terminalStateVariants"]
        self.assertEqual({"discriminator", "oneOf"}, set(variants))
        self.assertEqual("DB provenance + startedAt/mcpInputHash presence", variants["discriminator"])
        self.assertEqual(["preStart", "startedPreDispatch", "postDispatch"], list(variants["oneOf"]))
        self.assertEqual(["startedAt", "mcpInputHash"], variants["oneOf"]["preStart"]["omitted"])
        self.assertEqual(["startedAt"], variants["oneOf"]["startedPreDispatch"]["required"])
        self.assertEqual(["mcpInputHash"], variants["oneOf"]["startedPreDispatch"]["omitted"])
        self.assertEqual(["startedAt", "mcpInputHash"], variants["oneOf"]["postDispatch"]["required"])
        for variant in variants["oneOf"].values():
            self.assertEqual([], variant["nullable"])
        pre_example = self.contract["examples"]["failedPreStart"]
        middle_example = self.contract["examples"]["cancelledStartedPreDispatch"]
        post_example = self.contract["examples"]["failedPostDispatch"]
        self.assertNotIn("startedAt", pre_example)
        self.assertNotIn("mcpInputHash", pre_example)
        self.assertIn("startedAt", middle_example)
        self.assertNotIn("mcpInputHash", middle_example)
        self.assertIn("startedAt", post_example)
        self.assertIn("mcpInputHash", post_example)

    def test_terminal_payload_validator_allows_started_only_but_rejects_post_dispatch_hash_loss(self):
        from scripts.validate_schedule_ai_contract import validate_terminal_payload

        base = {
            "status": "failed",
            "completedAt": "2026-08-26T12:00:10+09:00",
            "failure": {"code": "FAILED", "detail": "실패했습니다.", "retryable": True},
        }
        started_only = {**base, "startedAt": "2026-08-26T12:00:02+09:00"}
        self.assertEqual([], validate_terminal_payload(started_only, "startedPreDispatch"))
        self.assertNotEqual([], validate_terminal_payload(started_only, "postDispatch"))
        post_dispatch = {**started_only, "mcpInputHash": "b" * 64}
        self.assertEqual([], validate_terminal_payload(post_dispatch, "postDispatch"))
        self.assert_mutation_rejected(
            lambda contract: contract["examples"]["cancelledStartedPreDispatch"].update(
                mcpInputHash="b" * 64
            )
        )
        self.assert_mutation_rejected(
            lambda contract: contract["examples"]["failedPostDispatch"].pop("mcpInputHash")
        )

    def test_terminal_db_provenance_mapping_is_exact(self):
        self.assertEqual(
            {
                "preStart": "run.started_at IS NULL and no matching MCP call log",
                "startedPreDispatch": "run.started_at IS NOT NULL and no matching MCP call log",
                "postDispatch": "run.started_at IS NOT NULL and matching #52 MCP call log owns validated mcpInputHash",
            },
            self.contract["databasePolicy"]["terminalProvenance"],
        )
        self.assert_mutation_rejected(
            lambda contract: contract["databasePolicy"]["terminalProvenance"].update(
                startedPreDispatch="run.started_at IS NOT NULL"
            )
        )

    def test_running_is_closed_started_pre_dispatch_or_post_dispatch_oneof(self):
        variants = self.contract["runningStateVariants"]
        self.assertEqual(
            "DB provenance + startedAt/mcpInputHash presence",
            variants["discriminator"],
        )
        self.assertEqual(["startedPreDispatch", "postDispatch"], list(variants["oneOf"]))
        self.assertEqual(["startedAt"], variants["oneOf"]["startedPreDispatch"]["required"])
        self.assertEqual(["mcpInputHash"], variants["oneOf"]["startedPreDispatch"]["omitted"])
        self.assertEqual(
            ["startedAt", "mcpInputHash"],
            variants["oneOf"]["postDispatch"]["required"],
        )
        self.assertEqual(
            ["startedPreDispatch", "postDispatch"],
            self.contract["stateResponses"]["running"]["oneOf"],
        )

    def test_running_started_only_and_polling_to_terminal_transitions_preserve_provenance(self):
        from scripts.validate_schedule_ai_contract import (
            validate_running_payload,
            validate_terminal_payload,
        )

        running = {
            "status": "running",
            "startedAt": "2026-08-26T12:00:02+09:00",
        }
        self.assertEqual([], validate_running_payload(running, "startedPreDispatch"))
        self.assertNotEqual([], validate_running_payload(running, "postDispatch"))

        for terminal_status in ["failed", "cancelled"]:
            terminal = {
                **running,
                "status": terminal_status,
                "completedAt": "2026-08-26T12:00:03+09:00",
                "failure": {"code": "STOPPED", "detail": "중단되었습니다.", "retryable": False},
            }
            self.assertEqual([], validate_terminal_payload(terminal, "startedPreDispatch"))

            dispatched_running = {**running, "mcpInputHash": "b" * 64}
            self.assertEqual([], validate_running_payload(dispatched_running, "postDispatch"))
            dispatched_terminal = {**terminal, "mcpInputHash": "b" * 64}
            self.assertEqual([], validate_terminal_payload(dispatched_terminal, "postDispatch"))
            dispatched_terminal.pop("mcpInputHash")
            self.assertNotEqual([], validate_terminal_payload(dispatched_terminal, "postDispatch"))

        self.assert_mutation_rejected(
            lambda contract: contract["stateResponses"]["running"].update(
                oneOf=["postDispatch"]
            )
        )

    def test_create_503_is_intake_only_and_worker_unavailability_still_accepts(self):
        condition = next(item for item in self.contract["problemConditions"] if item["code"] == "ASYNC_INTAKE_UNAVAILABLE")
        self.assertIn("persistence", condition["condition"])
        self.assertIn("queue admission", condition["condition"])
        self.assertNotIn("MCP", condition["condition"])
        self.assertEqual(
            "202 queued is durable even when worker or private MCP is unavailable",
            self.contract["intakeIsolationPolicy"]["workerUnavailable"],
        )
        self.assertNotIn("ASYNC_COMPUTE_UNAVAILABLE", self.contract["problemMatrix"]["503"])

    def test_get_body_uses_forbidden_sentinel_and_rejects_empty_object_or_null(self):
        sentinel = self.contract["schemas"]["BodyForbidden"]
        self.assertEqual({"kind": "forbidden", "accepts": []}, sentinel)
        get_endpoints = [endpoint for endpoint in self.contract["endpoints"] if endpoint["method"] == "GET"]
        get_projection = [endpoint for endpoint in self.contract["catalogProjection"] if endpoint["method"] == "GET"]
        self.assertTrue(get_endpoints)
        self.assertTrue(all(endpoint["schemas"]["body"] == "BodyForbidden" for endpoint in get_endpoints))
        self.assertTrue(all(endpoint["schemas"]["body"] == "BodyForbidden" for endpoint in get_projection))

    def test_readback_and_endpoint_owner_bindings_are_exact_and_mutation_sensitive(self):
        self.assertEqual(
            {"generationResult":"generationResultRead","revisionResult":"revisionResultRead"},
            self.contract["ownerBindings"]["readback"],
        )
        self.assertEqual(6, len(self.contract["ownerBindings"]["endpoints"]))
        self.assert_mutation_rejected(
            lambda contract: contract["ownerBindings"]["readback"].update(generationResult="generationWorkerAndCandidate")
        )

    def test_every_problem_code_has_condition_and_exact_example(self):
        expected_codes = {code for codes in self.contract["problemMatrix"].values() for code in codes}
        conditions = {condition["code"]: condition for condition in self.contract["problemConditions"]}
        self.assertEqual(expected_codes, set(conditions))
        for condition in conditions.values():
            self.assertEqual(
                {"code", "status", "condition", "type", "title", "detail", "fieldErrors", "endpoints", "example"},
                set(condition),
            )
            self.assertTrue(condition["endpoints"])
            self.assertEqual(
                {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"},
                set(condition["example"]),
            )

    def test_each_endpoint_has_exact_problem_code_matrix_and_resolvable_condition_scope(self):
        endpoint_ids = {f"{method} {path}" for method, path in IDENTITIES}
        groups = self.contract["endpointGroups"]
        for members in groups.values():
            self.assertTrue(members)
            self.assertLessEqual(set(members), endpoint_ids)
        scoped_codes = {endpoint_id: set() for endpoint_id in endpoint_ids}
        for condition in self.contract["problemConditions"]:
            for group in condition["endpoints"]:
                for endpoint_id in groups[group]:
                    scoped_codes[endpoint_id].add(condition["code"])
        for endpoint in self.contract["endpoints"]:
            endpoint_id = f'{endpoint["method"]} {endpoint["path"]}'
            matrix_codes = {code for codes in endpoint["errorMatrix"].values() for code in codes}
            self.assertEqual(scoped_codes[endpoint_id], matrix_codes)
            self.assertEqual({str(status) for status in endpoint["errors"]}, set(endpoint["errorMatrix"]))

    def test_eight_implementation_owners_are_exact(self):
        owner_keys = {key for key in self.contract["implementationOwners"] if key not in {"commandSnapshotMigration", "workerLifecycle"}}
        self.assertEqual(
            {"generationIntake", "generationResultRead", "generationWorkerAndCandidate", "generationApply", "revisionIntake", "revisionResultRead", "revisionWorkerAndCandidate", "revisionApply"},
            owner_keys,
        )

    def test_catalog_projection_rejects_each_field_mutation(self):
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        for identity in IDENTITIES:
            selected = next(item for item in catalog["endpoints"] if (item["method"], item["path"]) == identity)
            for field in selected:
                with self.subTest(identity=identity, field=field):
                    candidate = copy.deepcopy(catalog)
                    target = next(item for item in candidate["endpoints"] if (item["method"], item["path"]) == identity)
                    target[field] = "mutated"
                    result = self.validate(self.contract, candidate)
                    self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_catalog_projection_is_exact_for_all_six_rows(self):
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        selected = [item for item in catalog["endpoints"] if (item["method"], item["path"]) in IDENTITIES]
        self.assertEqual(self.contract["catalogProjection"], selected)

    def test_unrelated_catalog_endpoint_does_not_affect_schedule_ai_projection(self):
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        unrelated = next(item for item in catalog["endpoints"] if (item["method"], item["path"]) not in IDENTITIES)
        unrelated["owner"] = "unrelated mutation outside Issue #89"
        result = self.validate(self.contract, catalog)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
