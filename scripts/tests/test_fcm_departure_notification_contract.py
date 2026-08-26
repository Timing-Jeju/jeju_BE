from __future__ import annotations

import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/contracts/domains/fcm-departure-notification/contract.json"
DOCUMENT = ROOT / "docs/contracts/domains/fcm-departure-notification/contract.md"
FIXTURE = ROOT / "fixtures/contracts/fcm-departure-notification/cases.json"
VALIDATOR = ROOT / "scripts/validate_fcm_departure_notification_contract.py"
ARCHITECTURE = ROOT / "docs/ARCHITECTURE.md"
RDB_API_SPEC = ROOT / "docs/designs/timing-jeju-backend-rdb-api-spec.md"
DEFINITION_OF_DONE = ROOT / "docs/DEFINITION_OF_DONE.md"
SPRING_README = ROOT / "services/spring-api/README.md"


class FcmDepartureNotificationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        missing = [path.relative_to(ROOT).as_posix() for path in (CONTRACT, DOCUMENT, FIXTURE, VALIDATOR) if not path.is_file()]
        if missing:
            raise AssertionError(f"#112 필수 계약 산출물이 없습니다: {missing}")
        cls.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        cls.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
        spec = importlib.util.spec_from_file_location("fcm_departure_validator", VALIDATOR)
        assert spec is not None and spec.loader is not None
        cls.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.validator)

    def test_identity_scope_and_fail_closed_readiness_are_exact(self) -> None:
        self.assertEqual("timing-jeju-fcm-departure-notification/v1", self.contract["schemaVersion"])
        self.assertEqual("1.0.0", self.contract["contractVersion"])
        self.assertEqual(112, self.contract["ownerIssue"])
        self.assertEqual([72, 73, 93], self.contract["dependencies"])
        readiness = self.contract["readiness"]
        self.assertTrue(readiness["contractReady"])
        self.assertFalse(readiness["implementationReady"])
        self.assertFalse(readiness["productionDefaultEnabled"])
        self.assertEqual("fail_closed", readiness["missingPreconditionAction"])
        self.assertIn("live-state contract #93 implemented", readiness["activationPreconditions"])

    def test_logical_job_and_per_device_attempt_identities_are_separate(self) -> None:
        job = self.contract["jobPolicy"]
        attempt = self.contract["attemptPolicy"]
        self.assertEqual(115, job["persistenceOwnerIssue"])
        self.assertEqual("notification_jobs", job["table"])
        self.assertEqual(
            ["tripId", "scheduleVersionId", "tripItemId", "tripLegId", "notificationType", "scheduledAt"],
            job["logicalJobKey"],
        )
        self.assertNotIn("pushDeviceId", job["logicalJobKey"])
        self.assertEqual(116, attempt["persistenceOwnerIssue"])
        self.assertEqual("push_delivery_attempts", attempt["table"])
        self.assertEqual(["jobId", "pushDeviceId", "attemptNo"], attempt["deliveryAttemptKey"])
        self.assertEqual("one logical job and one attempt per active device", attempt["fanOut"])
        self.assertEqual("reject_without_overwrite", attempt["duplicateAttemptAction"])
        keys = self.validator.build_attempt_keys(
            "71000000-0000-0000-0000-000000000001",
            ["72000000-0000-0000-0000-000000000001", "72000000-0000-0000-0000-000000000002"],
            1,
        )
        self.assertEqual(2, len(keys))
        self.assertEqual(2, len(set(keys)))
        with self.assertRaisesRegex(ValueError, "중복"):
            self.validator.build_attempt_keys(
                "71000000-0000-0000-0000-000000000001",
                ["72000000-0000-0000-0000-000000000001"] * 2,
                1,
            )

    def test_job_and_attempt_states_transitions_and_terminality_are_closed(self) -> None:
        job = self.contract["jobPolicy"]
        attempt = self.contract["attemptPolicy"]
        self.assertEqual(["PENDING", "LEASED", "RETRY", "ACCEPTED", "CANCELLED", "DEAD"], job["states"])
        self.assertEqual(["ACCEPTED", "CANCELLED", "DEAD"], job["terminalStates"])
        self.assertEqual(
            ["RESERVED", "CALL_STARTED", "ACCEPTED", "RETRYABLE_FAILURE", "PERMANENT_FAILURE", "SKIPPED", "ACCEPTANCE_UNKNOWN"],
            attempt["states"],
        )
        self.assertEqual(
            ["ACCEPTED", "RETRYABLE_FAILURE", "PERMANENT_FAILURE", "SKIPPED", "ACCEPTANCE_UNKNOWN"],
            attempt["terminalStates"],
        )
        expected = {
            ("PENDING", "LEASED"), ("PENDING", "CANCELLED"), ("PENDING", "DEAD"),
            ("LEASED", "LEASED"), ("LEASED", "ACCEPTED"), ("LEASED", "RETRY"), ("LEASED", "CANCELLED"), ("LEASED", "DEAD"),
            ("RETRY", "LEASED"), ("RETRY", "CANCELLED"), ("RETRY", "DEAD"),
        }
        self.assertEqual(expected, {(item["from"], item["to"]) for item in job["allowedTransitions"]})
        self.assertEqual(len(expected), len(job["allowedTransitions"]))
        for terminal in job["terminalStates"]:
            self.assertFalse(any(item["from"] == terminal for item in job["allowedTransitions"]))

    def test_any_job_transition_or_attempt_state_add_remove_is_rejected(self) -> None:
        for parent, field in (("jobPolicy", "allowedTransitions"), ("attemptPolicy", "states")):
            original = self.contract[parent][field]
            removed = copy.deepcopy(self.contract)
            removed[parent][field].pop()
            with self.subTest(path=(parent, field), mutation="remove"), self.assertRaises(ValueError):
                self.validator.validate_contract(removed, self.fixture)
            added = copy.deepcopy(self.contract)
            added[parent][field].append(copy.deepcopy(original[0]))
            with self.subTest(path=(parent, field), mutation="add"), self.assertRaises(ValueError):
                self.validator.validate_contract(added, self.fixture)

    def test_completion_cas_generation_lease_and_retry_matrix_is_complete(self) -> None:
        attempt = self.contract["attemptPolicy"]
        expected = {
            "all_match_with_unexpired_lease", "job_not_leased", "lease_owner_mismatch", "lease_expired",
            "generation_mismatch", "fencing_token_mismatch", "terminal_job", "attempt_absent",
            "attempt_not_call_started", "target_not_in_flight", "second_completion",
            "retry_after_expiry", "retry_attempt_limit_reached",
        }
        matrix = attempt["completionCasMatrix"]
        self.assertEqual(expected, {case["case"] for case in matrix})
        self.assertEqual(len(expected), len(matrix))
        by_case = {case["case"]: case for case in matrix}
        self.assertEqual("apply_attempt_and_job_transition_atomically", by_case["all_match_with_unexpired_lease"]["action"])
        for name in expected - {"all_match_with_unexpired_lease", "retry_after_expiry", "retry_attempt_limit_reached"}:
            self.assertFalse(by_case[name]["writesAllowed"])
        for name in ("retry_after_expiry", "retry_attempt_limit_reached"):
            self.assertTrue(by_case[name]["writesAllowed"])
            self.assertEqual("persist_current_retryable_attempt_and_job_dead_atomically", by_case[name]["action"])
        retry = attempt["retryPolicy"]
        self.assertEqual(3, retry["maximumAttemptsPerDevice"])
        self.assertTrue(retry["retryAfterPrecedence"])
        self.assertTrue(retry["mustRemainBeforeExpiresAt"])

    def test_provider_outcome_taxonomy_distinguishes_ambiguous_post_write_timeout(self) -> None:
        outcomes = {item["outcome"]: item for item in self.contract["attemptPolicy"]["providerOutcomeMatrix"]}
        self.assertEqual("RETRYABLE_FAILURE", outcomes["explicit_transient_rejection"]["attemptStatus"])
        self.assertTrue(outcomes["explicit_transient_rejection"]["retryAllowed"])
        self.assertEqual("RETRYABLE_FAILURE", outcomes["provable_pre_connect_failure"]["attemptStatus"])
        self.assertTrue(outcomes["provable_pre_connect_failure"]["retryAllowed"])
        ambiguous = outcomes["post_write_ambiguous"]
        self.assertEqual("ACCEPTANCE_UNKNOWN", ambiguous["attemptStatus"])
        self.assertTrue(ambiguous["terminal"])
        self.assertFalse(ambiguous["retryAllowed"])
        self.assertEqual("ACCEPTED", outcomes["provider_message_id_received"]["attemptStatus"])

    def test_attempt_is_durably_reserved_and_marked_call_started_before_provider_io(self) -> None:
        dispatch = self.contract["attemptPolicy"]["dispatchReservation"]
        self.assertEqual(["RESERVED", "CALL_STARTED"], dispatch["inFlightStates"])
        self.assertEqual(
            ["jobId", "pushDeviceId", "attemptNo", "leaseOwner", "generation", "fencingToken"],
            dispatch["reservationCasKey"],
        )
        self.assertEqual("insert RESERVED exact attempt key and commit", dispatch["beforeProviderCall"])
        self.assertEqual("CAS RESERVED to CALL_STARTED and commit before any provider I/O", dispatch["callStartMarker"])
        self.assertEqual("provider call forbidden", dispatch["reservationOrMarkerCasFailure"])
        self.assertEqual("single push_delivery_attempts row", dispatch["statusStorage"])
        self.assertEqual("same row CAS", dispatch["statusMutation"])
        self.assertTrue(dispatch["terminalImmutable"])

    def test_crash_and_expired_lease_recovery_is_fail_closed(self) -> None:
        matrix = self.contract["attemptPolicy"]["crashRecoveryMatrix"]
        expected = {
            "crash_before_call_marker",
            "crash_after_call_marker_before_write",
            "crash_after_write_before_result",
            "crash_after_result_before_completion",
            "expired_lease_reserved",
            "expired_lease_call_started",
        }
        self.assertEqual(expected, {item["case"] for item in matrix})
        by_case = {item["case"]: item for item in matrix}
        for name in ("crash_before_call_marker", "expired_lease_reserved"):
            self.assertEqual("RETRYABLE_FAILURE", by_case[name]["attemptStatus"])
            self.assertTrue(by_case[name]["retryAllowed"])
        for name in expected - {"crash_before_call_marker", "expired_lease_reserved"}:
            self.assertEqual("ACCEPTANCE_UNKNOWN", by_case[name]["attemptStatus"])
            self.assertFalse(by_case[name]["retryAllowed"])
        for item in matrix:
            self.assertTrue(item["sameAttemptRow"])
            self.assertEqual("current owner/generation/fencing/unexpired lease CAS", item["requiredCas"])

    def test_expired_leased_job_is_reclaimed_same_state_with_new_fence(self) -> None:
        policy = self.contract["jobPolicy"]["expiredLeaseReclaim"]
        self.assertEqual("LEASED", policy["fromState"])
        self.assertEqual("LEASED", policy["toState"])
        self.assertEqual(["generation", "targetSnapshot", "attemptRows"], policy["preserve"])
        self.assertEqual(["leaseOwner", "leaseUntil"], policy["replace"])
        self.assertEqual(
            ["expectedState", "expectedLeaseOwner", "expectedLeaseUntil", "expectedGeneration", "expectedFencingToken"],
            policy["casKey"],
        )
        self.assertEqual("increment", policy["fencingToken"])
        self.assertEqual("reject", policy["oldFenceCompletion"])
        for case in self.fixture["leaseReclaimCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"], self.validator.evaluate_lease_reclaim_case(case, self.contract))

    def test_target_snapshot_is_persisted_closed_and_aggregation_precedence_is_executable(self) -> None:
        target = self.contract["attemptPolicy"]["targetSnapshot"]
        self.assertEqual("push_delivery_targets", target["table"])
        self.assertEqual(["jobId", "pushDeviceId"], target["key"])
        self.assertEqual(
            ["jobId", "pushDeviceId", "ordinal", "currentState", "currentAttemptNo", "capturedAt"],
            target["closedFields"],
        )
        self.assertEqual(
            ["UNATTEMPTED", "RESERVED", "IN_FLIGHT", "RETRYABLE", "ACCEPTED", "ACCEPTANCE_UNKNOWN", "PERMANENT_FAILURE", "SKIPPED"],
            target["states"],
        )
        self.assertFalse(target["claimTransactionStoresTargets"])
        self.assertEqual("claim commit 후 첫 pre-send preparation transaction에서 live eligibility 재검사 후 저장", target["capturePoint"])
        self.assertEqual(
            ["leaseOwner", "generation", "fencingToken", "unexpiredLeaseUntil"],
            target["preparationCas"],
        )
        for case in self.fixture["aggregationCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"], self.validator.aggregate_target_states(case, self.contract))

    def test_claim_preparation_and_post_snapshot_races_are_executable(self) -> None:
        policy = self.contract["attemptPolicy"]["targetRacePolicy"]
        self.assertEqual(
            ["user setting", "OS permission", "latest required location consent", "device active"],
            policy["immediatelyBeforeEachTargetCall"],
        )
        self.assertEqual("include when active at preparation", policy["claimToPreparationActivation"])
        self.assertEqual("exclude when inactive at preparation", policy["claimToPreparationDeactivation"])
        self.assertEqual("do not add to current job", policy["postSnapshotActivation"])
        self.assertEqual("SKIPPED without provider call", policy["postSnapshotDeviceDeactivation"])
        self.assertEqual("CANCELLED and stop remaining calls", policy["postSnapshotJobWideInvalidation"])
        for case in self.fixture["targetRaceCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"], self.validator.evaluate_target_race_case(case, self.contract))

    def test_preparation_with_zero_eligible_targets_cancels_atomically(self) -> None:
        case = next(item for item in self.fixture["targetRaceCases"] if item["id"] == "deactivated_between_claim_and_preparation")
        self.assertEqual(
            {"action": "CANCELLED", "reason": "NO_ACTIVE_PUSH_TARGET", "snapshotDevices": [], "attemptCount": 0, "providerCallCount": 0, "atomic": True},
            self.validator.evaluate_target_race_case(case, self.contract),
        )

    def test_completion_requires_existing_call_started_attempt_and_inflight_target(self) -> None:
        completion = self.contract["attemptPolicy"]["completionPolicy"]
        self.assertTrue(completion["existingExactAttemptRequired"])
        self.assertEqual("CALL_STARTED", completion["requiredAttemptStatus"])
        self.assertEqual("IN_FLIGHT", completion["requiredTargetState"])
        self.assertEqual(
            ["attempt terminal CAS", "target transition", "job aggregation"],
            completion["sameTransaction"],
        )
        self.assertEqual(
            ["absent_attempt", "reserved_attempt", "terminal_attempt", "wrong_target_marker", "second_completion", "cas_mismatch"],
            completion["rejectionCases"],
        )
        for case in self.fixture["completionCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"], self.validator.evaluate_completion_case(case, self.contract))

    def test_target_transition_graph_and_retry_attempt_increment_are_closed(self) -> None:
        policy = self.contract["attemptPolicy"]["targetTransitionPolicy"]
        expected = {
            ("UNATTEMPTED", "RESERVED"), ("RETRYABLE", "RESERVED"), ("RESERVED", "IN_FLIGHT"),
            ("IN_FLIGHT", "ACCEPTED"), ("IN_FLIGHT", "RETRYABLE"),
            ("IN_FLIGHT", "ACCEPTANCE_UNKNOWN"), ("IN_FLIGHT", "PERMANENT_FAILURE"),
            ("UNATTEMPTED", "SKIPPED"), ("RETRYABLE", "SKIPPED"),
        }
        self.assertEqual(expected, {(item["from"], item["to"]) for item in policy["allowedTransitions"]})
        self.assertEqual(len(expected), len(policy["allowedTransitions"]))
        self.assertEqual(
            [
                {"from": "RESERVED", "to": "RETRYABLE", "trigger": "expired lease proves provider call not started"},
                {"from": "IN_FLIGHT", "to": "ACCEPTANCE_UNKNOWN", "trigger": "expired lease after call marker"},
            ],
            policy["recoveryTransitions"],
        )
        self.assertEqual("currentAttemptNo + 1 exact key", policy["retryReservationAttemptNo"])
        self.assertEqual("unique(jobId,pushDeviceId,attemptNo)", policy["duplicateReservationConstraint"])
        self.assertEqual("reject_without_provider_call", policy["duplicateReservationAction"])
        self.assertEqual(
            ["attempt row", "target row", "job aggregation"],
            policy["sameCasTransaction"],
        )
        for case in self.fixture["targetTransitionCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"], self.validator.evaluate_target_transition_case(case, self.contract))

    def test_inactive_retry_target_inserts_new_terminal_skipped_attempt_atomically(self) -> None:
        policy = self.contract["attemptPolicy"]["retryInactiveSkipPolicy"]
        self.assertEqual("RETRYABLE and inactive immediately before call", policy["precondition"])
        self.assertEqual("currentAttemptNo + 1", policy["newAttemptNo"])
        self.assertEqual("SKIPPED", policy["attemptStatus"])
        self.assertEqual("RETRYABLE to SKIPPED", policy["targetTransition"])
        self.assertEqual(["attempt insert", "target transition", "job aggregation"], policy["sameCasTransaction"])
        self.assertEqual(0, policy["providerCallCount"])
        self.assertEqual("reject_without_mutation_or_provider_call", policy["duplicateOrStaleAction"])
        for case in self.fixture["retryInactiveSkipCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"], self.validator.evaluate_retry_inactive_skip_case(case, self.contract))

    def test_generation_has_one_canonical_name(self) -> None:
        reclaim = self.contract["jobPolicy"]["expiredLeaseReclaim"]
        self.assertEqual(["generation", "targetSnapshot", "attemptRows"], reclaim["preserve"])
        self.assertIn("expectedGeneration", reclaim["casKey"])
        for case in self.fixture["leaseReclaimCases"]:
            self.assertIn("generation", case)
            self.assertIn("expectedGeneration", case)

    def test_exhausted_or_expired_transient_attempt_is_persisted_before_job_dead(self) -> None:
        for case in self.fixture["retryExhaustionCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"], self.validator.complete_retryable_attempt(case, self.contract))

    def test_schedule_fixture_covers_dst_overlap_gap_and_buffer_boundaries(self) -> None:
        ids = {case["id"] for case in self.fixture["scheduleCases"]}
        self.assertTrue({
            "dst_overlap_earlier_offset_fails_closed", "dst_overlap_later_offset_fails_closed", "dst_gap_fails_closed", "default_buffer_is_ten_minutes",
            "zero_buffer_is_allowed", "maximum_buffer_is_inclusive", "negative_buffer_fails_closed",
            "above_maximum_buffer_fails_closed", "non_integer_buffer_fails_closed", "overflow_buffer_fails_closed",
        } <= ids)
        for case in self.fixture["scheduleCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"], self.validator.evaluate_schedule_case(case, self.contract))

    def test_safety_buffer_contract_matches_issue_113_preference(self) -> None:
        buffer = self.contract["schedulePolicy"]["safetyBuffer"]
        self.assertEqual("safetyBufferMinutes", buffer["field"])
        self.assertEqual("minutes", buffer["unit"])
        self.assertEqual(10, buffer["default"])
        self.assertEqual(0, buffer["minimum"])
        self.assertEqual(120, buffer["maximum"])
        self.assertTrue(buffer["inclusiveBounds"])
        self.assertTrue(buffer["integerOnly"])
        self.assertTrue(buffer["zeroAllowed"])
        self.assertEqual("fail_closed", buffer["overflowAction"])

    def test_latest_required_location_consent_and_audit_snapshot_matrix(self) -> None:
        consent = self.contract["consentPolicy"]
        self.assertEqual(
            ["osNotificationPermissionGranted", "serverDepartureNotificationEnabled", "latestRequiredLocationConsent"],
            consent["requiredSignals"],
        )
        location = consent["locationConsentEvaluation"]
        self.assertEqual("location", location["documentType"])
        self.assertEqual("latest effective required document at evaluation instant", location["requiredVersionSource"])
        self.assertEqual("exact version match and active consent", location["eligibilityRule"])
        self.assertEqual(
            ["documentType", "requiredVersion", "consentedVersion", "consentStatus", "evaluatedAt"],
            location["auditSnapshotFields"],
        )
        self.assertEqual(["atSchedule", "immediatelyBeforeSend"], consent["checkpoints"])
        ids = {case["id"] for case in self.fixture["consentCases"]}
        self.assertTrue({"latest_version_active", "old_version", "newer_non_required_version", "withdrawn"} <= ids)
        for case in self.fixture["consentCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"], self.validator.evaluate_consent_case(case))

    def test_consent_versions_and_status_are_canonical_or_fail_closed(self) -> None:
        validation = self.contract["consentPolicy"]["locationConsentEvaluation"]
        self.assertEqual("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", validation["canonicalVersionPattern"])
        self.assertEqual(["ACTIVE", "WITHDRAWN"], validation["allowedStatuses"])
        invalid_ids = {case["id"] for case in self.fixture["consentCases"] if case["id"].startswith("invalid_")}
        self.assertEqual(
            {"invalid_missing_required_version", "invalid_null_consented_version", "invalid_blank_required_version", "invalid_wrong_type_version", "invalid_unknown_status"},
            invalid_ids,
        )
        for case in self.fixture["consentCases"]:
            if case["id"].startswith("invalid_"):
                with self.subTest(case=case["id"]):
                    self.assertEqual(
                        {"eligible": False, "reason": "invalid_consent_evidence", "auditSnapshot": None},
                        self.validator.evaluate_consent_case(case),
                    )

    def test_safety_buffer_change_invalidates_and_recomputes_atomically(self) -> None:
        policy = self.contract["schedulePolicy"]["safetyBufferChange"]
        self.assertEqual("safetyBufferMinutes changed", policy["trigger"])
        self.assertEqual(
            ["compare preference version", "invalidate old generation", "cancel old unsent job", "persist new preference", "recompute notifyAt", "create new logical job or record expired omission"],
            policy["atomicOrder"],
        )
        for case in self.fixture["safetyBufferChangeCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"], self.validator.evaluate_buffer_change_case(case, self.contract))

    def test_message_is_user_visible_and_data_schema_is_closed(self) -> None:
        message = self.contract["messagePolicy"]
        self.assertEqual("notification+data", message["messageType"])
        self.assertEqual("high", message["androidPriority"])
        schema = message["dataSchema"]
        required = ["contractVersion", "tripId", "tripItemId", "scheduleVersionId", "deepLink"]
        self.assertEqual(required, schema["required"])
        self.assertFalse(schema["additionalProperties"])
        self.assertTrue(all(item["type"] == "string" for item in schema["properties"].values()))
        self.assertEqual(64, schema["maxKeyUtf8Bytes"])
        self.assertEqual(512, schema["maxValueUtf8Bytes"])
        self.assertEqual(2048, schema["maxTotalUtf8Bytes"])

    def test_data_payload_rejects_non_string_uuid_encoding_control_and_utf8_overflow(self) -> None:
        payload = copy.deepcopy(self.fixture["messageCases"][0]["data"])
        self.validator.validate_data_payload(payload, self.contract)
        mutations = []
        value = copy.deepcopy(payload); value["tripId"] = 7; mutations.append(value)
        value = copy.deepcopy(payload); value["tripId"] = "71000000-0000-0000-0000-00000000000A"; mutations.append(value)
        value = copy.deepcopy(payload); value["unknown"] = "x" * 65; mutations.append(value)
        value = copy.deepcopy(payload); value["contractVersion"] = "가" * 200; mutations.append(value)
        value = copy.deepcopy(payload); value["deepLink"] += "%0A"; mutations.append(value)
        value = copy.deepcopy(payload); value["deepLink"] = value["deepLink"].replace("live?", "live\n?"); mutations.append(value)
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.validator.validate_data_payload(mutation, self.contract)

    def test_utf8_key_value_and_total_budgets_are_independently_enforced(self) -> None:
        schema = self.contract["messagePolicy"]["dataSchema"]
        self.validator.validate_utf8_budgets({"a": "가" * 170}, schema)
        with self.assertRaisesRegex(ValueError, "key UTF-8"):
            self.validator.validate_utf8_budgets({"가" * 22: "x"}, schema)
        with self.assertRaisesRegex(ValueError, "value UTF-8"):
            self.validator.validate_utf8_budgets({"a": "가" * 171}, schema)
        individually_valid_but_total_oversized = {
            f"k{index}": "가" * 170 for index in range(5)
        }
        with self.assertRaisesRegex(ValueError, "전체 UTF-8"):
            self.validator.validate_utf8_budgets(individually_valid_but_total_oversized, schema)

    def test_title_body_utf8_caps_have_deterministic_unicode_fallback(self) -> None:
        notification = self.contract["messagePolicy"]["notification"]
        self.assertEqual(80, notification["titleMaxUtf8Bytes"])
        self.assertEqual(256, notification["bodyMaxUtf8Bytes"])
        self.assertEqual("출발 알림", notification["fallback"]["title"])
        self.assertEqual("앱을 열어 다음 일정을 확인하세요.", notification["fallback"]["body"])
        for case in self.fixture["messageCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expectedNotification"], self.validator.build_notification(case, self.contract))

    def test_ttl_is_capped_and_equality_is_expired(self) -> None:
        ttl = self.contract["messagePolicy"]["ttlPolicy"]
        self.assertEqual(900, ttl["maximumSeconds"])
        self.assertEqual("do_not_send", ttl["nonPositiveAction"])
        for case in self.fixture["ttlCases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expectedTtlSeconds"], self.validator.calculate_ttl_seconds(case["sendAttemptAt"], case["validUntil"], 900))

    def test_duplicate_json_keys_are_rejected_by_actual_cli_without_traceback(self) -> None:
        cases = (
            ("contract_root", "contract", '{"schemaVersion":"a","schemaVersion":"b"}', FIXTURE),
            ("contract_nested", "contract", '{"readiness":{"flag":true,"flag":false}}', FIXTURE),
            ("fixture_root", "fixture", '{"contractVersion":"a","contractVersion":"b"}', CONTRACT),
            ("fixture_nested", "fixture", '{"contractVersion":"1.0.0","scheduleCases":[{"id":"a","id":"b"}]}', CONTRACT),
        )
        for name, kind, raw, companion in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                duplicate = Path(directory) / f"{name}.json"
                duplicate.write_text(raw, encoding="utf-8")
                args = [sys.executable, str(VALIDATOR)]
                if kind == "contract":
                    args.extend(["--contract", str(duplicate), "--fixture", str(companion)])
                else:
                    args.extend(["--contract", str(companion), "--fixture", str(duplicate)])
                completed = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)
                self.assertEqual(1, completed.returncode)
                self.assertIn("중복 JSON key", completed.stderr)
                self.assertRegex(completed.stderr, "[가-힣]")
                self.assertNotIn("Traceback", completed.stderr)

    def test_contract_fixture_and_transition_models_are_recursively_closed(self) -> None:
        self.validator.validate_contract(self.contract, self.fixture)
        mutations = []
        extra = copy.deepcopy(self.contract); extra["jobPolicy"]["unexpected"] = True; mutations.append(extra)
        removed = copy.deepcopy(self.contract); removed["attemptPolicy"]["providerOutcomeMatrix"].pop(); mutations.append(removed)
        weakened = copy.deepcopy(self.contract); weakened["consentPolicy"]["requiredSignals"].remove("latestRequiredLocationConsent"); mutations.append(weakened)
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.validator.validate_contract(mutation, self.fixture)

    def test_spring_is_sole_owner_and_fcm_acceptance_is_not_delivery(self) -> None:
        ownership = self.contract["ownership"]
        self.assertEqual(
            ["token registry", "logical job", "per-device attempt", "cancel", "dispatch", "retry", "provider acceptance state"],
            ownership["springOwns"],
        )
        self.assertIn("FCM credential", ownership["fastApiForbidden"])
        delivery = self.contract["deliveryPolicy"]
        self.assertEqual("accepted", delivery["providerMessageIdMeaning"])
        self.assertFalse(delivery["deliveredClaimAllowed"])
        self.assertEqual("GET /api/v1/trips/{tripId}/live-state", delivery["appReentrySourceOfTruth"])

    def test_provider_post_write_ambiguity_is_closed_over_timeout_reset_and_eof(self) -> None:
        ambiguity = self.contract["attemptPolicy"]["postWriteAmbiguity"]
        self.assertEqual(
            ["write_timeout", "read_timeout", "connection_reset", "unexpected_eof"],
            ambiguity["transportExamples"],
        )
        self.assertEqual("ACCEPTANCE_UNKNOWN", ambiguity["attemptStatus"])
        self.assertFalse(ambiguity["retryAllowed"])
        self.assertEqual("request bytes provably not sent", self.contract["attemptPolicy"]["provablePreConnectBoundary"])

    def test_future_issue_readback_evidence_replaces_pending_amendments(self) -> None:
        traceability = self.contract["traceability"]
        self.assertNotIn("pmIssueAmendmentsRequired", traceability)
        evidence = {item["issue"]: item for item in traceability["issueReadbackEvidence"]}
        self.assertEqual({113, 114, 115, 116}, set(evidence))
        self.assertEqual("2026-08-26T03:40:05Z", evidence[113]["updatedAt"])
        for issue in (114, 115):
            self.assertEqual("2026-08-26T03:57:27Z", evidence[issue]["updatedAt"])
        self.assertEqual("2026-08-26T04:33:51Z", evidence[116]["updatedAt"])
        self.assertEqual(
            "de24ed51cd99f944a6a0ed10eba089252e906f8fbb25e2ff0789bc5ea6ebd5da",
            evidence[116]["blankLineNormalizedBodySha256"],
        )
        self.assertEqual(
            ["default 10 and integer 0..120 inclusive", "latest required location consent"],
            evidence[113]["appliedMarkers"],
        )
        self.assertEqual(
            ["closed FCM data UTF-8 budgets", "provable pre-connect versus post-write/read ambiguity", "unexpected EOF is post-write ambiguous"],
            evidence[114]["appliedMarkers"],
        )
        self.assertEqual(
            ["device-independent logical job key", "one logical job per notification", "safetyBuffer version-CAS atomic replacement"],
            evidence[115]["appliedMarkers"],
        )
        self.assertEqual(
            [
                "exact per-device attempt key",
                "ACCEPTANCE_UNKNOWN no retry",
                "lease generation fencing",
                "push_delivery_targets closed snapshot and current states",
                "RESERVED/CALL_STARTED durable pre-I/O protocol",
                "marker-based crash and expired-lease recovery",
                "mutually exclusive target aggregation precedence",
                "exhausted transient attempt persistence",
                "single-row RESERVED/CALL_STARTED/terminal status lifecycle",
                "expired LEASED same-state reclaim with preserved generation and incremented fence",
                "post-claim preparation snapshot and claim/post-snapshot race rechecks",
                "existing CALL_STARTED plus IN_FLIGHT completion gate",
                "zero-target preparation cancellation",
                "closed target transitions and atomic attempt-target-job aggregation",
                "single generation naming",
                "inactive retry target terminal SKIPPED attempt",
            ],
            evidence[116]["appliedMarkers"],
        )

    def test_tokens_credentials_and_pii_are_denied(self) -> None:
        security = self.contract["securityPolicy"]
        self.assertEqual("sensitive device identifier", security["registrationTokenClassification"])
        self.assertEqual("ADC or secret mount only", security["firebaseCredentialInjection"])
        self.validator.assert_no_sensitive_values(self.fixture)
        for key in security["fixtureForbiddenKeys"]:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "민감정보"):
                self.validator.assert_no_sensitive_values({key: "forbidden"})

    def test_canonical_docs_have_exact_double_consent_latest_location_and_send_checkpoint(self) -> None:
        exact = (
            "OS 알림 권한", "서버 출발 알림 설정", "최신 required 위치 동의", "예약 시점과 발송 직전",
            "notification + data", "FCM 접수는 단말 전달 완료가 아니다", "Spring", "FastAPI", "fail-closed",
            "RESERVED", "CALL_STARTED", "push_delivery_targets", "safetyBufferMinutes", "unexpected EOF",
            "preparation", "single generation", "old fence", "NO_ACTIVE_PUSH_TARGET", "IN_FLIGHT", "RETRYABLE → SKIPPED",
        )
        for path in (ARCHITECTURE, RDB_API_SPEC, DEFINITION_OF_DONE, SPRING_README):
            source = path.read_text(encoding="utf-8")
            with self.subTest(path=path.relative_to(ROOT)):
                self.assertIn("Issue #112", source)
                for phrase in exact:
                    self.assertIn(phrase, source)

    def test_markdown_explains_split_identity_state_dst_payload_and_issue_drift(self) -> None:
        source = DOCUMENT.read_text(encoding="utf-8")
        for phrase in (
            "logical job", "delivery attempt", "ACCEPTANCE_UNKNOWN", "post-write/read timeout", "DST overlap",
            "최신 required 위치 동의", "UTF-8 byte", "Issue #114", "Issue #116", "RESERVED", "CALL_STARTED",
            "push_delivery_targets", "unexpected EOF", "issueReadbackEvidence",
            "same row", "LEASED → LEASED", "preparation transaction", "old fencing token",
            "RETRYABLE → SKIPPED", "de24ed51cd99f944a6a0ed10eba089252e906f8fbb25e2ff0789bc5ea6ebd5da",
        ):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, source)


if __name__ == "__main__":
    unittest.main()
