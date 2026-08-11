from __future__ import annotations

import copy
import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Callable, Iterator


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts" / "validate_trips_contract.py"
CONTRACT = ROOT / "docs" / "contracts" / "domains" / "trips" / "contract.json"
CATALOG = ROOT / "docs" / "contracts" / "rest" / "catalog.json"
FIXTURES = ROOT / "fixtures" / "contracts" / "trips"


class TripsContractTest(unittest.TestCase):
    @contextmanager
    def _temporary_repository(self) -> Iterator[Path]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for source in (
                VALIDATOR,
                CONTRACT,
                CATALOG,
                FIXTURES / "request.json",
                FIXTURES / "success.json",
                FIXTURES / "problem.json",
                ROOT / "docs" / "contracts" / "domains" / "trips" / "contract.md",
                ROOT / "supabase" / "migrations" / "20260728000000_initial_public_schema.sql",
            ):
                target = root / source.relative_to(ROOT)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
            yield root

    @staticmethod
    def _load(path: Path) -> Any:
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def _write(path: Path, value: Any) -> None:
        path.write_text(
            json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
            encoding="utf-8",
        )

    @staticmethod
    def _run(root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(root / "scripts" / "validate_trips_contract.py"), "--root", str(root)],
            cwd=root,
            capture_output=True,
            text=True,
            check=False,
        )

    @staticmethod
    def _digest(value: Any) -> str:
        payload = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode()
        return hashlib.sha256(payload).hexdigest()

    def test_repository_trips_contract_is_valid(self) -> None:
        result = self._run(ROOT)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("여행 CRUD 계약 검사 성공", result.stdout)

    def test_contract_is_closed_and_has_exact_endpoint_identities(self) -> None:
        contract = self._load(CONTRACT)
        self.assertEqual("timing-jeju-trips-contract/v1", contract["schemaVersion"])
        self.assertEqual("1.0.0", contract["contractVersion"])
        self.assertEqual("v1.1", contract["sourceSpecVersion"])
        self.assertEqual([44, 45], contract["implementationIssues"])
        self.assertEqual(
            [
                ("GET", "/api/v1/trips"),
                ("POST", "/api/v1/trips"),
                ("GET", "/api/v1/trips/{tripId}"),
                ("PATCH", "/api/v1/trips/{tripId}"),
                ("DELETE", "/api/v1/trips/{tripId}"),
            ],
            [(item["method"], item["path"]) for item in contract["endpoints"]],
        )

    def test_canonical_contract_cannot_be_changed_by_updating_only_the_digest(self) -> None:
        with self._temporary_repository() as root:
            path = root / CONTRACT.relative_to(ROOT)
            contract = self._load(path)
            contract["ownership"]["crossOwnerConcealment"] = 403
            self._write(path, contract)
            validator = root / VALIDATOR.relative_to(ROOT)
            source = validator.read_text(encoding="utf-8")
            source = source.replace(
                source.split('CANONICAL_CONTRACT_SHA256 = "', 1)[1].split('"', 1)[0],
                self._digest(contract),
                1,
            )
            validator.write_text(source, encoding="utf-8")
            result = self._run(root)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("semantic", result.stdout)

    def test_endpoint_semantics_are_independently_enforced(self) -> None:
        mutations: dict[str, Callable[[dict[str, Any]], None]] = {
            "list path": lambda c: c["endpoints"][0].update({"path": "/api/v1/me/trips"}),
            "post idempotency": lambda c: c["endpoints"][1]["idempotency"].update({"required": False}),
            "patch if-match": lambda c: c["endpoints"][3].update({"headersSchema": "CommonHeaders"}),
            "delete repeat": lambda c: c["deleteSemantics"].update({"repeat": "204"}),
            "timezone": lambda c: c["tripPolicy"].update({"timezone": "UTC"}),
            "owner": lambda c: c["ownership"].update({"source": "email"}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                path = root / CONTRACT.relative_to(ROOT)
                contract = self._load(path)
                mutate(contract)
                self._write(path, contract)
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("canonical", result.stdout)

    def test_catalog_projection_is_exact_and_not_falsely_ready(self) -> None:
        with self._temporary_repository() as root:
            path = root / CATALOG.relative_to(ROOT)
            catalog = self._load(path)
            domain = next(item for item in catalog["domainContracts"] if item["domain"] == "trips")
            domain["versions"]["notion"] = "1.0.0"
            domain["readiness"]["metadata"] = {"status": "ready", "evidence": {"notionPage": "guessed"}}
            self._write(path, catalog)
            result = self._run(root)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("catalog canonical", result.stdout)

    def test_strict_json_rejects_duplicate_and_non_finite_values(self) -> None:
        raw_values = (
            '{"contractVersion":"1.0.0","list":{"method":"GET","method":"POST"}}',
            '{"contractVersion":"1.0.0","list":{"query":{"size":NaN}}}',
            '{"contractVersion":"1.0.0","list":{"query":{"size":Infinity}}}',
        )
        for raw in raw_values:
            with self.subTest(raw=raw), self._temporary_repository() as root:
                (root / FIXTURES.relative_to(ROOT) / "request.json").write_text(raw, encoding="utf-8")
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("Traceback", result.stdout + result.stderr)

    def test_request_fixture_enforces_closed_http_envelopes(self) -> None:
        mutations: dict[str, Callable[[dict[str, Any]], None]] = {
            "unknown header": lambda v: v["create"]["headers"].update({"X-Api-Key": "secret"}),
            "missing if-match": lambda v: v["patchMaintain"]["headers"].pop("If-Match"),
            "wrong path id": lambda v: v["detail"]["pathParameters"].update({"tripId": "60000000-0000-0000-0000-000000000001"}),
            "delete body": lambda v: v["delete"].update({"body": {}}),
            "unknown body": lambda v: v["create"]["body"].update({"serviceRole": "forbidden"}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                path = root / FIXTURES.relative_to(ROOT) / "request.json"
                value = self._load(path)
                mutate(value)
                self._write(path, value)
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("request fixture", result.stdout)

    def test_date_timezone_and_transport_boundaries_are_validated(self) -> None:
        mutations: dict[str, Callable[[dict[str, Any]], None]] = {
            "invalid date": lambda v: v["create"]["body"].update({"startDate": "2026-02-30"}),
            "reversed range": lambda v: v["create"]["body"].update({"endDate": "2026-08-01"}),
            "too long": lambda v: v["create"]["body"].update({"endDate": "2026-09-03"}),
            "timezone": lambda v: v["create"]["body"].update({"timezone": "Asia/Jeju"}),
            "priority gap": lambda v: v["create"]["body"]["transportModes"][1].update({"priority": 3}),
            "duplicate mode": lambda v: v["create"]["body"]["transportModes"][1].update({"mode": "public_transit"}),
            "two primary": lambda v: v["create"]["body"]["transportModes"][1].update({"primary": True}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                path = root / FIXTURES.relative_to(ROOT) / "request.json"
                value = self._load(path)
                mutate(value)
                self._write(path, value)
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("semantic", result.stdout)

    def test_success_fixture_validates_total_score_provenance_and_freshness(self) -> None:
        mutations: dict[str, Callable[[dict[str, Any]], None]] = {
            "score without provenance": lambda v: v["list"]["body"]["items"][1].update({"scoreProvenance": None}),
            "provenance without score": lambda v: v["detail"]["body"].update({"totalScore": None}),
            "wrong source": lambda v: v["detail"]["body"]["scoreProvenance"].update({"source": "trip_plan"}),
            "invalid freshness": lambda v: v["detail"]["body"]["scoreProvenance"].update({"expiresAt": "2026-08-03T08:00:00+09:00"}),
            "stale mismatch": lambda v: v["detail"]["body"]["scoreProvenance"].update({"stale": True}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                path = root / FIXTURES.relative_to(ROOT) / "success.json"
                value = self._load(path)
                mutate(value)
                self._write(path, value)
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("score semantic", result.stdout)

    def test_cursor_and_list_order_invariants_are_validated(self) -> None:
        mutations: dict[str, Callable[[dict[str, Any]], None]] = {
            "cursor missing": lambda v: v["list"]["body"]["page"].update({"hasNext": True, "nextCursor": None}),
            "terminal cursor": lambda v: v["list"]["body"]["page"].update({"hasNext": False, "nextCursor": "opaque"}),
            "unstable order": lambda v: v["list"]["body"]["items"].reverse(),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                path = root / FIXTURES.relative_to(ROOT) / "success.json"
                value = self._load(path)
                mutate(value)
                self._write(path, value)
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("pagination semantic", result.stdout)

    def test_patch_presence_and_schedule_effect_matrix_are_closed(self) -> None:
        contract = self._load(CONTRACT)
        matrix = contract["patchSemantics"]["scheduleEffectMatrix"]
        self.assertEqual(
            {
                "title": "maintain",
                "userPace": "invalidate-and-require-regeneration",
                "transportModes": "invalidate-and-require-regeneration",
                "startDate": "reject-with-regeneration-required-when-any-schedule-version-exists",
                "endDate": "reject-with-regeneration-required-when-any-schedule-version-exists",
                "timezone": "reject-with-regeneration-required-when-any-schedule-version-exists",
            },
            matrix,
        )
        self.assertEqual("preserve", contract["patchSemantics"]["omitted"])
        self.assertEqual("reject", contract["patchSemantics"]["null"])
        self.assertEqual("replace", contract["patchSemantics"]["collections"])

    def test_success_envelopes_and_patch_effects_are_exact(self) -> None:
        mutations: dict[str, Callable[[dict[str, Any]], None]] = {
            "create replay": lambda v: v["createReplay"]["headers"].update({"Idempotency-Replayed": "false"}),
            "missing etag": lambda v: v["patchInvalidate"]["headers"].pop("ETag"),
            "maintain effect": lambda v: v["patchMaintain"]["body"].update({"scheduleEffect": "invalidated"}),
            "invalidate effect": lambda v: v["patchInvalidate"]["body"].update({"regenerationRequired": False}),
            "delete body": lambda v: v["delete"].update({"body": {}}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                path = root / FIXTURES.relative_to(ROOT) / "success.json"
                value = self._load(path)
                mutate(value)
                self._write(path, value)
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("success fixture", result.stdout)

    def test_problem_fixture_has_exact_conditions_status_codes_and_types(self) -> None:
        for field, replacement in (("status", 500), ("code", "ARBITRARY"), ("type", "https://example.com/problem")):
            with self.subTest(field=field), self._temporary_repository() as root:
                path = root / FIXTURES.relative_to(ROOT) / "problem.json"
                value = self._load(path)
                value["409_trip_regeneration_required"][field] = replacement
                self._write(path, value)
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("problem fixture", result.stdout)

    def test_storage_drift_and_delete_lineage_policy_are_explicit(self) -> None:
        contract = self._load(CONTRACT)
        storage = contract["storage"]
        self.assertEqual([44, 45], storage["implementationIssues"])
        self.assertEqual("supabase/migrations", storage["migrationSourceOfTruth"])
        self.assertFalse(storage["flywayAllowed"])
        self.assertEqual(
            ["timezone", "revision", "owner-write-rls"],
            [item["id"] for item in storage["schemaDrift"]],
        )
        deletion = contract["deleteSemantics"]
        self.assertEqual("cascade", deletion["tripAggregate"])
        self.assertEqual("delete-with-aggregate", deletion["locationAndExecutionHistory"])
        self.assertEqual("preserve", deletion["externalImportLineage"])
        self.assertEqual("preserve", deletion["userAndAuthIdentity"])

    def test_external_traceability_does_not_claim_missing_design_states(self) -> None:
        contract = self._load(CONTRACT)
        notion = contract["externalTraceability"]["notion"]
        figma = contract["externalTraceability"]["figma"]
        self.assertEqual("not-ready", notion["status"])
        self.assertEqual(5, len(notion["pages"]))
        self.assertEqual("not-ready", figma["status"])
        self.assertEqual("251:4347", figma["pageNodeId"])
        self.assertEqual("182:3248", figma["observedNodes"][0]["nodeId"])
        self.assertEqual("not-observed", figma["loading"])
        self.assertEqual("not-observed", figma["empty"])
        self.assertEqual("not-observed", figma["error"])

    def test_quality_gates_run_the_trips_contract_validator(self) -> None:
        shell = (ROOT / "scripts" / "quality-gate.sh").read_text(encoding="utf-8")
        powershell = (ROOT / "scripts" / "quality-gate.ps1").read_text(encoding="utf-8")
        self.assertIn("python3 scripts/validate_trips_contract.py", shell)
        self.assertIn("py -3 scripts/validate_trips_contract.py", powershell)

    def test_markdown_is_korean_and_keeps_implementation_out_of_scope(self) -> None:
        document = (ROOT / "docs" / "contracts" / "domains" / "trips" / "contract.md").read_text(encoding="utf-8")
        for phrase in (
            "여행 CRUD API canonical 계약",
            "추가 결정을 하지 않아도",
            "canonical sub",
            "Flyway를 도입하지 않는다",
            "Controller·Service·Repository를 구현하지 않는다",
            "#44",
            "#45",
        ):
            self.assertIn(phrase, document)


if __name__ == "__main__":
    unittest.main()
