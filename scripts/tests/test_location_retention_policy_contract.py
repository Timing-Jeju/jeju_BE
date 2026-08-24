import copy
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/contracts/domains/location-retention/contract.json"
DOCUMENT = ROOT / "docs/contracts/domains/location-retention/contract.md"
FIXTURE = ROOT / "fixtures/contracts/location-retention/policy.json"
VALIDATOR = ROOT / "scripts/validate_location_retention_contract.py"
SHELL_GATE = ROOT / "scripts/quality-gate.sh"
POWERSHELL_GATE = ROOT / "scripts/quality-gate.ps1"


def _powershell_gate_toolchain_available(runtime: str | None, launcher: str | None) -> bool:
    return runtime is not None and launcher is not None


class LocationRetentionPolicyContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        missing = [path.relative_to(ROOT).as_posix() for path in (CONTRACT, DOCUMENT, FIXTURE, VALIDATOR) if not path.is_file()]
        if missing:
            raise AssertionError(f"#73 필수 계약 산출물이 없습니다: {missing}")
        cls.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        cls.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
        spec = importlib.util.spec_from_file_location("location_retention_validator", VALIDATOR)
        assert spec is not None and spec.loader is not None
        cls.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.validator)

    def test_identity_scope_and_release_gate_are_exact(self) -> None:
        self.assertEqual("timing-jeju-location-retention-policy/v1", self.contract["schemaVersion"])
        self.assertEqual("1.0.0", self.contract["contractVersion"])
        self.assertEqual(73, self.contract["ownerIssue"])
        self.assertEqual(
            {
                "developmentAllowed": True,
                "internalQaAllowed": True,
                "productionDefaultEnabled": False,
                "releaseIssue": 168,
                "privacyLegalCompleted": False,
                "governmentFilingCompleted": False,
                "activationRule": "all Issue #168 evidence must be complete before production enable",
            },
            self.contract["releaseGate"],
        )
        self.assertEqual(
            ["government filing", "app-store submission", "TTL job", "public location API", "continuous tracking"],
            self.contract["excludedScope"],
        )

    def test_consent_version_required_and_optional_feature_matrix_is_closed(self) -> None:
        consent = self.contract["consentPolicy"]
        self.assertEqual(
            {"documentType", "initialVersion", "requiredFor", "availableWithoutConsent", "missingConsentError", "withdrawal"},
            set(consent),
        )
        self.assertEqual("location", consent["documentType"])
        self.assertEqual("2026-08-11.v1", consent["initialVersion"])
        self.assertEqual(
            ["live_state", "execution_event", "idle_time_recommendation", "recovery_option", "live_recalculation", "departure_notification"],
            consent["requiredFor"],
        )
        self.assertEqual(
            ["place_search", "place_detail", "trip_manual_crud", "schedule_manual_crud"],
            consent["availableWithoutConsent"],
        )
        self.assertEqual("LOCATION_CONSENT_REQUIRED", consent["missingConsentError"])
        self.assertEqual("block new location processing immediately and schedule redaction", consent["withdrawal"])

    def test_processing_policy_is_closed_complete_and_has_five_canonical_targets(self) -> None:
        expected_ids = {
            "trip_execution_event_location",
            "live_state_current_location",
            "async_command_location",
            "fastapi_mcp_location",
            "observability_location",
        }
        policies = self.contract["processingPolicies"]
        self.assertEqual(expected_ids, {policy["id"] for policy in policies})
        self.assertEqual(5, len(policies))
        expected_keys = {"id", "target", "purpose", "storage", "precision", "retention", "deletionTriggers", "disposition"}
        for policy in policies:
            with self.subTest(policy=policy["id"]):
                self.assertEqual(expected_keys, set(policy))
                self.assertTrue(policy["purpose"].strip())
                self.assertTrue(policy["precision"].strip())
                self.assertTrue(policy["retention"])
                self.assertTrue(policy["deletionTriggers"])
                self.assertTrue(policy["disposition"].strip())

    def test_storage_precision_and_retention_rules_match_product_policy(self) -> None:
        policies = {item["id"]: item for item in self.contract["processingPolicies"]}
        event = policies["trip_execution_event_location"]
        self.assertEqual("allowed", event["storage"])
        self.assertEqual("WGS84 point; accuracy at most 100m", event["precision"])
        self.assertEqual(
            {"mode": "earliestOf", "rules": [{"anchor": "tripEndedAt", "duration": "P7D"}, {"anchor": "lastEventAt", "duration": "P14D"}]},
            event["retention"],
        )
        live = policies["live_state_current_location"]
        self.assertEqual(
            {"mode": "earliestOf", "rules": [{"anchor": "tripEndedAt", "duration": "PT24H"}, {"anchor": "createdAt", "duration": "PT72H"}]},
            live["retention"],
        )
        command = policies["async_command_location"]
        self.assertEqual("limited", command["storage"])
        self.assertEqual("100m grid or place/stop id preferred", command["precision"])
        self.assertEqual(
            {"mode": "earliestOf", "rules": [{"anchor": "terminalAt", "duration": "PT24H"}, {"anchor": "tripEndedAt", "duration": "PT24H"}]},
            command["retention"],
        )
        self.assertEqual("forbidden", policies["fastapi_mcp_location"]["storage"])
        self.assertEqual("forbidden", policies["observability_location"]["storage"])

    def test_retention_cutoff_and_equality_boundary_are_executable(self) -> None:
        policies = {item["id"]: item for item in self.contract["processingPolicies"]}
        for case in self.fixture["retentionCases"]:
            with self.subTest(case=case["id"]):
                cutoff = self.validator.retention_cutoff(
                    policies[case["policyId"]]["retention"], case["anchors"], case["evaluatedAt"]
                )
                self.assertEqual(case["expectedCutoff"], cutoff.isoformat() if cutoff else None)
                self.assertEqual(case["expectedDue"], self.validator.is_expired(case["evaluatedAt"], cutoff))

    def test_missing_and_future_anchors_are_ignored_and_later_arrival_recomputes(self) -> None:
        policy = {item["id"]: item for item in self.contract["processingPolicies"]}["async_command_location"]["retention"]
        self.assertIsNone(
            self.validator.retention_cutoff(
                policy,
                {"terminalAt": None, "tripEndedAt": "2026-08-30T00:00:00+00:00"},
                "2026-08-20T00:00:00+00:00",
            )
        )
        self.assertFalse(self.validator.is_expired("2026-08-20T00:00:00+00:00", None))
        recomputed = self.validator.retention_cutoff(
            policy,
            {"terminalAt": "2026-08-20T00:00:00+00:00", "tripEndedAt": None},
            "2026-08-21T00:00:00+00:00",
        )
        self.assertEqual("2026-08-21T00:00:00+00:00", recomputed.isoformat())
        self.assertTrue(self.validator.is_expired("2026-08-21T00:00:00+00:00", recomputed))

    def test_deletion_workflows_are_deterministic_and_preserve_only_non_location_lineage(self) -> None:
        self.assertEqual(
            {
                "consentWithdrawal": ["record_withdrawal", "disable_location_features", "reject_new_location", "redact_live", "redact_events", "redact_commands", "preserve_non_location_audit"],
                "tripEnd": ["end_trip", "redact_live_within_24h", "redact_event_location_by_7d", "preserve_non_location_progress"],
                "tripDeletion": ["block_location", "redact_or_delete_owned_location", "preserve_external_shared_facts", "preserve_redacted_run_lineage"],
                "accountDeletion": ["block_new_requests", "redact_location_first", "delete_owned_aggregates", "delete_auth_user", "preserve_minimal_non_location_job_status"],
            },
            self.contract["deletionWorkflows"],
        )
        audit = self.contract["auditPolicy"]
        self.assertEqual(
            ["userId", "runId", "tripId", "scheduleVersionId", "eventType", "occurredAt", "deletionJobId", "status", "policyVersion", "retentionRule", "redactedAt", "externalSnapshotHash", "errorCode", "traceId"],
            audit["allowedNonLocationFields"],
        )
        self.assertEqual(
            {
                "publicDatabaseDirectAccess": "forbidden",
                "springApi": "canonical JWT sub and owned trip authorization required before location processing",
                "serviceRole": "server-only redaction and retention jobs",
                "fastApi": "no database, JWT or credential access; bounded redacted facts only",
                "crossUserAccess": "forbidden",
            },
            self.contract["accessPolicy"],
        )

    def test_each_deletion_workflow_has_exact_closed_redact_delete_preserve_fields(self) -> None:
        expected = {
            "consentWithdrawal": {
                "redact": ["trip_execution_events.location", "live_state_snapshots.current_location", "async_command_snapshot.location_fields", "mcp_compute_call_logs.location_fields"],
                "delete": [],
                "preserve": ["userId", "runId", "tripId", "scheduleVersionId", "eventType", "status", "policyVersion", "retentionRule", "redactedAt", "errorCode", "traceId"],
            },
            "tripEnd": {
                "redact": ["live_state_snapshots.current_location", "trip_execution_events.location"],
                "delete": [],
                "preserve": ["tripId", "scheduleVersionId", "eventType", "occurredAt", "runId", "status", "policyVersion", "redactedAt"],
            },
            "tripDeletion": {
                "redact": ["async_command_snapshot.location_fields", "mcp_compute_call_logs.location_fields"],
                "delete": ["trip_execution_events.*", "live_state_snapshots.*"],
                "preserve": ["runId", "status", "policyVersion", "redactedAt", "externalSnapshotHash", "errorCode", "traceId"],
            },
            "accountDeletion": {
                "redact": ["async_command_snapshot.location_fields", "mcp_compute_call_logs.location_fields"],
                "delete": ["trip_execution_events.*", "live_state_snapshots.*", "user_owned_trip_aggregates.*", "auth.users.id"],
                "preserve": ["deletionJobId", "status", "policyVersion", "redactedAt", "errorCode", "traceId"],
            },
        }
        self.assertEqual(expected, self.contract["deletionFieldActions"])
        self.assertEqual(set(self.contract["deletionWorkflows"]), set(self.contract["deletionFieldActions"]))
        audit_fields = set(self.contract["auditPolicy"]["allowedNonLocationFields"])
        for workflow, actions in self.contract["deletionFieldActions"].items():
            with self.subTest(workflow=workflow):
                self.assertEqual({"redact", "delete", "preserve"}, set(actions))
                self.assertEqual(len(actions["redact"]), len(set(actions["redact"])))
                self.assertEqual(len(actions["delete"]), len(set(actions["delete"])))
                self.assertEqual(len(actions["preserve"]), len(set(actions["preserve"])))
                self.assertLessEqual(set(actions["preserve"]), audit_fields)

        for workflow in expected:
            for action in ("redact", "delete", "preserve"):
                mutation = copy.deepcopy(self.contract)
                if mutation["deletionFieldActions"][workflow][action]:
                    mutation["deletionFieldActions"][workflow][action].pop()
                else:
                    mutation["deletionFieldActions"][workflow][action].append("unexpected.field")
                with self.subTest(workflow=workflow, action=action), self.assertRaises(ValueError):
                    self.validator.validate_contract(mutation, self.fixture)

        missing_preserved_field = copy.deepcopy(self.contract)
        missing_preserved_field["auditPolicy"]["allowedNonLocationFields"].remove("occurredAt")
        with self.assertRaises(ValueError):
            self.validator.validate_contract(missing_preserved_field, self.fixture)

    def test_fixture_has_no_raw_coordinates_credentials_or_provider_payload(self) -> None:
        self.validator.assert_no_sensitive_fixture_values(self.fixture)
        prohibited = self.contract["securityPolicy"]["prohibitedFixtureKeys"]
        self.assertEqual(
            ["lat", "lng", "latitude", "longitude", "coordinates", "accuracyMeters", "accessToken", "providerToken", "apiKey", "prompt", "completion", "rawPayload"],
            prohibited,
        )
        for key in prohibited:
            with self.subTest(key=key):
                with self.assertRaisesRegex(ValueError, "민감 위치 또는 비밀정보"):
                    self.validator.assert_no_sensitive_fixture_values({key: "forbidden"})
        with self.assertRaisesRegex(ValueError, "좌표쌍"):
            self.validator.assert_no_sensitive_fixture_values({"value": "33.458111,126.941516"})

    def test_contract_and_fixture_are_recursively_closed_and_mutations_fail(self) -> None:
        self.validator.validate_contract(self.contract, self.fixture)
        self.assertEqual(self.validator.CANONICAL_CONTRACT_SHA256, self.validator.canonical_digest(self.contract))
        self.assertEqual(self.validator.CANONICAL_FIXTURE_SHA256, self.validator.canonical_digest(self.fixture))
        mutations = []
        missing_purpose = copy.deepcopy(self.contract)
        del missing_purpose["processingPolicies"][0]["purpose"]
        mutations.append(missing_purpose)
        extra = copy.deepcopy(self.contract)
        extra["releaseGate"]["legalApproved"] = True
        mutations.append(extra)
        default_on = copy.deepcopy(self.contract)
        default_on["releaseGate"]["productionDefaultEnabled"] = True
        mutations.append(default_on)
        wrong_release = copy.deepcopy(self.contract)
        wrong_release["releaseGate"]["releaseIssue"] = 73
        mutations.append(wrong_release)
        weakened_ttl = copy.deepcopy(self.contract)
        weakened_ttl["processingPolicies"][0]["retention"]["rules"][0]["duration"] = "P30D"
        mutations.append(weakened_ttl)
        weakened_purpose = copy.deepcopy(self.contract)
        weakened_purpose["processingPolicies"][0]["purpose"] = "analytics"
        mutations.append(weakened_purpose)
        missing_trigger = copy.deepcopy(self.contract)
        missing_trigger["processingPolicies"][0]["deletionTriggers"].pop()
        mutations.append(missing_trigger)
        weakened_output_guard = copy.deepcopy(self.contract)
        weakened_output_guard["securityPolicy"]["prohibitedOutputs"].pop()
        mutations.append(weakened_output_guard)
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with self.assertRaises(ValueError):
                    self.validator.validate_contract(mutation, self.fixture)

    def test_cli_rejects_malformed_roots_without_traceback(self) -> None:
        for payload in ([], "string", None, {"schemaVersion": "wrong"}):
            with self.subTest(payload=payload), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "contract.json"
                path.write_text(json.dumps(payload), encoding="utf-8")
                completed = subprocess.run(
                    [sys.executable, str(VALIDATOR), "--contract", str(path), "--fixture", str(FIXTURE)],
                    cwd=ROOT,
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertEqual(1, completed.returncode)
                self.assertRegex(completed.stderr, "[가-힣]")
                self.assertNotIn("Traceback", completed.stderr)

    def test_markdown_keeps_legal_claims_truthful_and_release_gate_separate(self) -> None:
        source = DOCUMENT.read_text(encoding="utf-8")
        self.assertIn("법률기관의 승인값이 아니다", source)
        self.assertIn("Issue #168", source)
        self.assertIn("production default-off", source)
        self.assertIn("개발과 내부 QA는 허용", source)
        self.assertNotIn("신고 완료", source.replace("신고 완료를 추정하지 않는다", ""))

    def test_quality_gates_execute_validator(self) -> None:
        shell = SHELL_GATE.read_text(encoding="utf-8")
        powershell = POWERSHELL_GATE.read_text(encoding="utf-8")
        self.assertIn("python3 scripts/validate_location_retention_contract.py", shell)
        self.assertIn("py -3 scripts/validate_location_retention_contract.py", powershell)

    def test_powershell_common_contract_gate_is_actually_executable(self) -> None:
        if os.environ.get("TIMING_JEJU_POWERSHELL_GATE_CHILD") == "1":
            return
        source = POWERSHELL_GATE.read_text(encoding="utf-8")
        self.assertIn("function Write-Stage", source)
        runtime = shutil.which("pwsh") or shutil.which("powershell")
        launcher = shutil.which("py")
        if not _powershell_gate_toolchain_available(runtime, launcher):
            self.skipTest("PowerShell 또는 Windows py launcher가 없는 개발 환경입니다.")
        environment = os.environ.copy()
        environment["TIMING_JEJU_POWERSHELL_GATE_CHILD"] = "1"
        completed = subprocess.run(
            [runtime, "-NoProfile", "-File", str(POWERSHELL_GATE), "-SetupValidation", "-Scope", "common"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
            env=environment,
        )
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertIn("위치정보 수집·보존·삭제 정책 계약 검사", completed.stdout)
        self.assertIn("위치정보 보존 정책 계약 검증 성공", completed.stdout)

    def test_powershell_runtime_matrix_requires_windows_py_launcher(self) -> None:
        self.assertFalse(_powershell_gate_toolchain_available(None, None))
        self.assertFalse(_powershell_gate_toolchain_available("pwsh", None))
        self.assertFalse(_powershell_gate_toolchain_available(None, "py"))
        self.assertTrue(_powershell_gate_toolchain_available("pwsh", "py"))

    def test_powershell_native_validator_failure_makes_gate_fail_fast(self) -> None:
        if os.environ.get("TIMING_JEJU_POWERSHELL_GATE_CHILD") == "1":
            return
        source = POWERSHELL_GATE.read_text(encoding="utf-8")
        self.assertIn("function Invoke-Native", source)
        self.assertIn("$LASTEXITCODE", source)
        bare_native_calls = [
            line.strip()
            for line in source.splitlines()
            if line.strip().startswith("py -3")
        ]
        self.assertEqual([], bare_native_calls)

        runtime = shutil.which("pwsh") or shutil.which("powershell")
        launcher = shutil.which("py")
        if not _powershell_gate_toolchain_available(runtime, launcher):
            self.skipTest("PowerShell 또는 Windows py launcher가 없는 개발 환경입니다.")
        environment = os.environ.copy()
        environment["TIMING_JEJU_POWERSHELL_GATE_CHILD"] = "1"
        with tempfile.TemporaryDirectory() as tmp:
            shim = Path(tmp) / "py.cmd"
            shim.write_text(
                "@echo off\r\n"
                "echo %* | findstr /C:\"validate_location_retention_contract.py\" >nul\r\n"
                "if not errorlevel 1 exit /b 17\r\n"
                f'"{launcher}" %*\r\n'
                "exit /b %ERRORLEVEL%\r\n",
                encoding="utf-8",
            )
            environment["PATH"] = f"{tmp}{os.pathsep}{environment['PATH']}"
            completed = subprocess.run(
                [runtime, "-NoProfile", "-File", str(POWERSHELL_GATE), "-SetupValidation", "-Scope", "common"],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
                env=environment,
            )
        self.assertNotEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertIn("위치정보 수집·보존·삭제 정책 계약 검사", completed.stdout)
        self.assertNotIn("[품질 게이트] 모든 단계 성공", completed.stdout)


if __name__ == "__main__":
    unittest.main()
