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
CURSOR_PAGE_REQUEST = (
    ROOT
    / "services"
    / "spring-api"
    / "src"
    / "main"
    / "java"
    / "com"
    / "timingjeju"
    / "api"
    / "application"
    / "pagination"
    / "CursorPageRequest.java"
)
TRIP_CREATE_MIGRATION = (
    ROOT / "supabase" / "migrations" / "20260902000000_trip_create_contract.sql"
)
SMOKE_CHECK = ROOT / "db" / "queries" / "smoke_check.sql"


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
                ROOT / "supabase" / "migrations" / "20260810000000_api_idempotency_registry.sql",
                TRIP_CREATE_MIGRATION,
                SMOKE_CHECK,
                ROOT / "services" / "spring-api" / "src" / "main" / "java" / "com" / "timingjeju" / "api" / "application" / "idempotency" / "IdempotencyRequest.java",
                CURSOR_PAGE_REQUEST,
                ROOT / "services" / "spring-api" / "src" / "main" / "java" / "com" / "timingjeju" / "api" / "global" / "error" / "StandardProblemCode.java",
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
        mutations: dict[str, Callable[[dict[str, Any]], None]] = {
            "ownership": lambda c: c["ownership"].update({"crossOwnerConcealment": 403}),
            "trip path canonical UUID": lambda c: c["schemas"]["TripId"].update(
                {"format": "text"}
            ),
            "list data availability": lambda c: c["endpoints"][0]["responses"].update(
                {"errors": [400, 401]}
            ),
            "detail data availability": lambda c: c["endpoints"][2]["responses"].update(
                {"errors": [400, 401, 404]}
            ),
            "profile provisioning": lambda c: c["createSemantics"][
                "profileProvisioningErrors"
            ].update({"STORAGE_UNAVAILABLE": "500 INTERNAL_SERVER_ERROR"}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                path = root / CONTRACT.relative_to(ROOT)
                contract = self._load(path)
                mutate(contract)
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
            "post errors": lambda c: c["endpoints"][1]["responses"].update({"errors": [400, 401, 409, 422]}),
            "profile conflict mapping": lambda c: c["createSemantics"]["profileProvisioningErrors"].update({"EMAIL_OWNERSHIP_CONFLICT": "500 INTERNAL_SERVER_ERROR"}),
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

    def test_trip_create_profile_provisioning_error_mapping_is_stable_and_cause_free(self) -> None:
        contract = self._load(CONTRACT)
        self.assertEqual(
            [400, 401, 409, 422, 503], contract["endpoints"][1]["responses"]["errors"]
        )
        self.assertEqual(
            {
                "EMAIL_OWNERSHIP_CONFLICT": "409 PROFILE_CONFLICT",
                "PROVIDER_SUBJECT_CONFLICT": "409 PROFILE_CONFLICT",
                "INVALID_AUTH_IDENTITY": "503 TRIP_DATA_UNAVAILABLE",
                "STORAGE_UNAVAILABLE": "503 TRIP_DATA_UNAVAILABLE",
                "exposure": "cause-free Problem Details; no raw provider message or PII",
            },
            contract["createSemantics"]["profileProvisioningErrors"],
        )
        problems = self._load(FIXTURES / "problem.json")
        self.assertEqual("PROFILE_CONFLICT", problems["409_profile_conflict"]["code"])
        self.assertEqual(
            "TRIP_DATA_UNAVAILABLE", problems["503_trip_data_unavailable"]["code"]
        )

    def test_trip_reads_expose_stable_data_unavailable_contract(self) -> None:
        contract = self._load(CONTRACT)
        catalog = self._load(CATALOG)
        expected = {
            ("GET", "/api/v1/trips"): {"success": [200], "errors": [400, 401, 503]},
            ("GET", "/api/v1/trips/{tripId}"): {
                "success": [200],
                "errors": [400, 401, 404, 503],
            },
        }

        for identity, responses in expected.items():
            contract_endpoint = next(
                item
                for item in contract["endpoints"]
                if (item["method"], item["path"]) == identity
            )
            catalog_endpoint = next(
                item
                for item in catalog["endpoints"]
                if (item["method"], item["path"]) == identity
            )
            self.assertEqual(responses, contract_endpoint["responses"])
            self.assertEqual(responses, catalog_endpoint["responses"])

        problems = self._load(FIXTURES / "problem.json")
        self.assertEqual(503, problems["503_trip_data_unavailable"]["status"])
        self.assertEqual(
            "TRIP_DATA_UNAVAILABLE", problems["503_trip_data_unavailable"]["code"]
        )

    def test_trip_id_schema_requires_exact_lowercase_canonical_uuid_pattern(self) -> None:
        expected_pattern = (
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        )
        contract = self._load(CONTRACT)
        self.assertEqual(
            {
                "type": "string",
                "nullable": False,
                "format": "uuid",
                "pattern": expected_pattern,
            },
            contract["schemas"]["TripId"],
        )

        mutations: tuple[tuple[str, Callable[[dict[str, Any]], None]], ...] = (
            ("removed", lambda c: c["schemas"]["TripId"].pop("pattern", None)),
            (
                "uppercase allowed",
                lambda c: c["schemas"]["TripId"].update(
                    {"pattern": "^[0-9A-Fa-f-]{36}$"}
                ),
            ),
        )
        for label, mutate in mutations:
            with self.subTest(label=label), self._temporary_repository() as root:
                path = root / CONTRACT.relative_to(ROOT)
                value = self._load(path)
                mutate(value)
                self._write(path, value)
                validator = root / VALIDATOR.relative_to(ROOT)
                source = validator.read_text(encoding="utf-8")
                old_digest = source.split('CANONICAL_CONTRACT_SHA256 = "', 1)[1].split('"', 1)[0]
                validator.write_text(
                    source.replace(old_digest, self._digest(value), 1), encoding="utf-8"
                )
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("lowercase canonical UUID semantic", result.stdout)

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

    def test_catalog_trip_create_idempotency_matches_common_contract_semantically(self) -> None:
        contract = self._load(CONTRACT)
        catalog = self._load(CATALOG)
        contract_create = contract["endpoints"][1]["idempotency"]
        catalog_create = next(
            endpoint
            for endpoint in catalog["endpoints"]
            if endpoint["method"] == "POST" and endpoint["path"] == "/api/v1/trips"
        )["idempotency"]
        self.assertEqual(contract_create, catalog_create)
        self.assertEqual("409 IDEMPOTENCY_KEY_REUSED", catalog_create["payloadConflict"])
        self.assertEqual(
            "single writer; in-progress or reused key returns 409 IDEMPOTENCY_KEY_REUSED",
            catalog_create["concurrentRequest"],
        )
        contract_create_endpoint = contract["endpoints"][1]
        catalog_create_endpoint = next(
            endpoint
            for endpoint in catalog["endpoints"]
            if endpoint["method"] == "POST" and endpoint["path"] == "/api/v1/trips"
        )
        self.assertEqual(
            {"success": [201], "errors": [400, 401, 409, 422, 503]},
            contract_create_endpoint["responses"],
        )
        self.assertEqual(
            contract_create_endpoint["responses"], catalog_create_endpoint["responses"]
        )

    def test_catalog_trip_create_rejects_legacy_idempotency_codes_independent_of_digest(self) -> None:
        mutations = (
            ("payloadConflict", "409 IDEMPOTENCY_PAYLOAD_CONFLICT"),
            ("concurrentRequest", "single writer then replay or 409 IDEMPOTENCY_REQUEST_IN_PROGRESS"),
        )
        for field, legacy_value in mutations:
            with self.subTest(field=field), self._temporary_repository() as root:
                path = root / CATALOG.relative_to(ROOT)
                catalog = self._load(path)
                endpoint = next(
                    item
                    for item in catalog["endpoints"]
                    if item["method"] == "POST" and item["path"] == "/api/v1/trips"
                )
                endpoint["idempotency"][field] = legacy_value
                self._write(path, catalog)
                validator = root / VALIDATOR.relative_to(ROOT)
                source = validator.read_text(encoding="utf-8")
                projection = {
                    "domainContracts": [
                        item for item in catalog["domainContracts"] if item["domain"] == "trips"
                    ],
                    "endpoints": [
                        item
                        for item in catalog["endpoints"]
                        if item["path"] in {"/api/v1/trips", "/api/v1/trips/{tripId}"}
                    ],
                }
                old_digest = source.split('CANONICAL_CATALOG_SHA256 = "', 1)[1].split('"', 1)[0]
                validator.write_text(
                    source.replace(old_digest, self._digest(projection), 1), encoding="utf-8"
                )
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("catalog idempotency semantic", result.stdout)

    def test_trip_list_size_inherits_common_cursor_page_maximum(self) -> None:
        contract = self._load(CONTRACT)
        catalog = self._load(CATALOG)
        source = CURSOR_PAGE_REQUEST.read_text(encoding="utf-8")
        common_maximum = int(source.split("MAX_SIZE = ", 1)[1].split(";", 1)[0])
        catalog_list = next(
            endpoint
            for endpoint in catalog["endpoints"]
            if endpoint["method"] == "GET" and endpoint["path"] == "/api/v1/trips"
        )

        self.assertEqual(50, common_maximum)
        self.assertEqual(
            common_maximum,
            contract["schemas"]["TripsListQuery"]["properties"]["size"]["maximum"],
        )
        self.assertEqual(
            common_maximum,
            contract["schemas"]["CursorPage"]["properties"]["size"]["maximum"],
        )
        self.assertEqual(common_maximum, contract["endpoints"][0]["pagination"]["maxSize"])
        self.assertEqual(common_maximum, catalog_list["pagination"]["size"]["max"])
        document = (ROOT / "docs" / "contracts" / "domains" / "trips" / "contract.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("공통 `CursorPageRequest.MAX_SIZE`와 같은 50", document)
        self.assertNotIn("최대 크기는 100", document)

    def test_validator_rejects_common_cursor_page_maximum_drift(self) -> None:
        with self._temporary_repository() as root:
            path = root / CURSOR_PAGE_REQUEST.relative_to(ROOT)
            source = path.read_text(encoding="utf-8")
            path.write_text(source.replace("MAX_SIZE = 50", "MAX_SIZE = 40"), encoding="utf-8")
            result = self._run(root)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("pagination common", result.stdout)

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

    def test_create_idempotency_inherits_the_common_uuid_and_problem_contract(self) -> None:
        with self._temporary_repository() as root:
            contract_path = root / CONTRACT.relative_to(ROOT)
            contract = self._load(contract_path)
            contract["schemas"]["CreateTripHeaders"]["properties"]["Idempotency-Key"] = {
                "type": "string", "nullable": False, "pattern": "^[A-Za-z0-9._:-]{1,128}$"
            }
            self._write(contract_path, contract)
            result = self._run(root)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("idempotency common", result.stdout)

        for key in (None, "fixture-trip-create-001", "550E8400-E29B-41D4-A716-446655440000"):
            with self.subTest(key=key), self._temporary_repository() as root:
                path = root / FIXTURES.relative_to(ROOT) / "request.json"
                value = self._load(path)
                if key is None:
                    value["create"]["headers"].pop("Idempotency-Key")
                else:
                    value["create"]["headers"]["Idempotency-Key"] = key
                self._write(path, value)
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)

    def test_common_idempotency_sources_cannot_drift_silently(self) -> None:
        mutations = (
            (Path("supabase/migrations/20260810000000_api_idempotency_registry.sql"), "idempotency_key uuid not null", "idempotency_key text not null"),
            (Path("services/spring-api/src/main/java/com/timingjeju/api/application/idempotency/IdempotencyRequest.java"), "UUID.fromString(value)", "UUID.randomUUID()"),
            (Path("services/spring-api/src/main/java/com/timingjeju/api/global/error/StandardProblemCode.java"), "IDEMPOTENCY_KEY_REUSED(409", "IDEMPOTENCY_KEY_REUSED(422"),
        )
        for path, before, after in mutations:
            with self.subTest(path=path), self._temporary_repository() as root:
                target = root / path
                target.write_text(target.read_text(encoding="utf-8").replace(before, after), encoding="utf-8")
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("idempotency common", result.stdout)

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

    def test_every_request_transport_modes_collection_is_validated(self) -> None:
        for mutation in (
            lambda modes: modes[1].update({"priority": 3}),
            lambda modes: modes[1].update({"mode": modes[0]["mode"]}),
            lambda modes: modes[0].update({"mode": "spaceship"}),
        ):
            with self._temporary_repository() as root:
                path = root / FIXTURES.relative_to(ROOT) / "request.json"
                value = self._load(path)
                value["patchMaintain"]["body"] = copy.deepcopy(value["create"]["body"])
                mutation(value["patchMaintain"]["body"]["transportModes"])
                self._write(path, value)
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("transportModes", result.stdout)

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

    def test_every_scored_response_requires_response_time_for_stale_derivation(self) -> None:
        for name in ("detail", "patchMaintain"):
            with self.subTest(name=name), self._temporary_repository() as root:
                path = root / FIXTURES.relative_to(ROOT) / "success.json"
                value = self._load(path)
                value[name].pop("responseTime", None)
                self._write(path, value)
                result = self._run(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("responseTime", result.stdout)

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

    def test_list_order_uses_actual_instants_not_timestamp_text(self) -> None:
        with self._temporary_repository() as root:
            path = root / FIXTURES.relative_to(ROOT) / "success.json"
            value = self._load(path)
            value["list"]["body"]["items"][0]["updatedAt"] = "2026-08-03T00:30:00Z"
            value["list"]["body"]["items"][1]["updatedAt"] = "2026-08-03T09:20:00+09:00"
            self._write(path, value)
            result = self._run(root)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        with self._temporary_repository() as root:
            path = root / FIXTURES.relative_to(ROOT) / "success.json"
            value = self._load(path)
            value["list"]["body"]["items"][0]["updatedAt"] = "2026-08-03T09:10:00+09:00"
            value["list"]["body"]["items"][1]["updatedAt"] = "2026-08-03T00:20:00Z"
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
            ["revision"],
            [item["id"] for item in storage["schemaDrift"]],
        )
        self.assertEqual(
            {
                "soleWriter": "Spring API using service_role",
                "clientRoles": ["anon", "authenticated"],
                "clientTablePrivileges": [],
                "clientWritePolicyCount": 0,
                "serviceRoleTablePrivileges": ["SELECT", "INSERT", "UPDATE", "DELETE"],
                "serviceRoleDeniedTablePrivileges": ["TRUNCATE", "REFERENCES", "TRIGGER"],
                "issue44CreateOwnership": "canonical JWT sub owner predicate and one aggregate transaction",
                "issue45UpdateDeleteOwnership": "future Spring API owner predicate and transaction",
                "writeRlsPoliciesRequired": False,
            },
            storage["writeAccess"],
        )
        deletion = contract["deleteSemantics"]
        self.assertEqual("cascade", deletion["tripAggregate"])
        self.assertEqual("delete-with-aggregate", deletion["locationAndExecutionHistory"])
        self.assertEqual("preserve", deletion["externalImportLineage"])
        self.assertEqual("preserve", deletion["userAndAuthIdentity"])

    def test_legacy_owner_write_rls_text_cannot_match_zero_policy_migration_and_smoke(self) -> None:
        with self._temporary_repository() as root:
            path = root / CONTRACT.relative_to(ROOT)
            contract = self._load(path)
            contract["storage"].pop("writeAccess", None)
            contract["storage"]["schemaDrift"] = [
                {"id": "timezone", "ownerIssue": 44, "required": "trip_plans.timezone is absent"},
                {"id": "revision", "ownerIssue": 45, "required": "revision is absent"},
                {
                    "id": "owner-write-rls",
                    "ownerIssue": 44,
                    "required": "owner INSERT/UPDATE/DELETE RLS policies are absent",
                },
            ]
            self._write(path, contract)
            result = self._run(root)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("storage writer", result.stdout)

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
