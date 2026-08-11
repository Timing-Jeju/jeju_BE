from __future__ import annotations

import copy
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = REPOSITORY_ROOT / "scripts" / "validate_saved_places_contract.py"
CONTRACT = (
    REPOSITORY_ROOT
    / "docs"
    / "contracts"
    / "domains"
    / "saved-places"
    / "contract.json"
)
CATALOG = REPOSITORY_ROOT / "docs" / "contracts" / "rest" / "catalog.json"
FIXTURE_ROOT = REPOSITORY_ROOT / "fixtures" / "contracts" / "saved-places"


class SavedPlacesContractTest(unittest.TestCase):
    maxDiff = None

    def test_repository_saved_places_contract_is_valid(self) -> None:
        result = self._run_validator(REPOSITORY_ROOT)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("관심 장소 CRUD 계약 검사 성공", result.stdout)

    def test_contract_fixes_the_four_canonical_endpoint_identities(self) -> None:
        contract = self._load_json(CONTRACT)

        identities = [
            (endpoint["method"], endpoint["path"])
            for endpoint in contract["endpoints"]
        ]
        self.assertEqual(
            [
                ("GET", "/api/v1/me/saved-places"),
                ("POST", "/api/v1/me/saved-places"),
                ("PATCH", "/api/v1/me/saved-places/{placeId}"),
                ("DELETE", "/api/v1/me/saved-places/{placeId}"),
            ],
            identities,
        )

    def test_contract_fixates_presence_owner_concurrency_and_storage_decisions(self) -> None:
        contract = self._load_json(CONTRACT)

        self.assertEqual(34, contract["implementationIssue"])
        self.assertEqual(
            "follow-up implementation Issue #34",
            contract["storage"]["migrationOwner"],
        )
        self.assertEqual("canonical JWT sub", contract["ownership"]["principal"])
        self.assertEqual(
            ["user_id", "place_id"], contract["storage"]["uniqueKey"]
        )
        self.assertEqual("text[]", contract["storage"]["tags"]["databaseType"])
        self.assertEqual("replace", contract["patchSemantics"]["tags"]["array"])
        self.assertEqual("clear", contract["patchSemantics"]["memo"]["null"])
        self.assertEqual("reset-to-empty", contract["patchSemantics"]["tags"]["null"])
        self.assertEqual("reset-to-zero", contract["patchSemantics"]["priority"]["null"])
        self.assertEqual("clear", contract["patchSemantics"]["targetDay"]["null"])
        self.assertEqual("404", contract["deleteSemantics"]["repeat"])
        self.assertEqual("404", contract["ownership"]["crossOwnerConcealment"])

    def test_contract_fixates_duplicate_create_and_idempotency_key_conflict(self) -> None:
        contract = self._load_json(CONTRACT)
        semantics = contract["createSemantics"]

        self.assertEqual(201, semantics["firstCreate"]["status"])
        self.assertEqual(201, semantics["sameKeySamePayload"]["status"])
        self.assertTrue(semantics["sameKeySamePayload"]["replayed"])
        self.assertEqual(
            "IDEMPOTENCY_PAYLOAD_CONFLICT",
            semantics["sameKeyDifferentPayload"]["code"],
        )
        self.assertEqual(409, semantics["sameKeyDifferentPayload"]["status"])
        self.assertEqual(200, semantics["differentKeySameCurrentPayload"]["status"])
        self.assertEqual(
            "SAVED_PLACE_ALREADY_EXISTS",
            semantics["differentKeyDifferentPayload"]["code"],
        )

    def test_contract_fixates_cursor_sort_tag_and_scope(self) -> None:
        contract = self._load_json(CONTRACT)
        pagination = contract["pagination"]

        self.assertEqual("opaque", pagination["cursor"])
        self.assertEqual(20, pagination["size"]["default"])
        self.assertEqual(100, pagination["size"]["maximum"])
        self.assertEqual(
            ["canonicalSub", "tag", "sort", "size"], pagination["cursorScope"]
        )
        self.assertEqual("placeId ASC", pagination["tieBreaker"])
        self.assertEqual(
            ["saved_at DESC", "place_id ASC"],
            pagination["sorts"]["saved_at_desc"],
        )

    def test_contract_fixates_notion_and_figma_drift_without_false_readiness(self) -> None:
        contract = self._load_json(CONTRACT)
        external = contract["externalTraceability"]

        self.assertEqual("not-linked", external["notion"]["contractVersion"])
        self.assertEqual("not-ready", external["notion"]["status"])
        self.assertEqual("not-linked", external["figma"]["contractVersion"])
        self.assertEqual("not-ready", external["figma"]["status"])
        self.assertEqual(
            ["329:4937", "329:4975"], external["figma"]["observedNodes"]
        )
        self.assertEqual(
            ["loading", "empty", "error"], external["figma"]["missingStateEvidence"]
        )

    def test_contract_and_catalog_are_closed_against_unknown_fields(self) -> None:
        with self._temporary_repository() as root:
            contract_path = self._contract_path(root)
            contract = self._load_json(contract_path)
            contract["unexpected"] = True
            self._write_json(contract_path, contract)

            result = self._run_validator(root)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("허용되지 않은 필드", result.stdout)

    def test_canonical_schema_constraints_cannot_be_weakened(self) -> None:
        mutations = {
            "memo 최대 길이": lambda value: value["schemas"]["Memo"][
                "maxLength"
            ].__class__,
            "priority 최소값": lambda value: value["schemas"]["Priority"].update(
                {"minimum": -100}
            ),
            "targetDay 최대값": lambda value: value["schemas"]["TargetDay"].update(
                {"maximum": 9999}
            ),
            "tag uniqueItems": lambda value: value["schemas"]["Tags"].pop(
                "uniqueItems"
            ),
            "placeId format": lambda value: value["schemas"]["PlaceId"].update(
                {"format": "text"}
            ),
            "problem status enum": lambda value: value["schemas"]["ProblemDetails"][
                "properties"
            ]["status"]["enum"].append(500),
            "response required field": lambda value: value["schemas"]["SavedPlace"][
                "required"
            ].remove("updatedAt"),
            "object additionalProperties": lambda value: value["schemas"][
                "SavedPlace"
            ].update({"additionalProperties": True}),
        }
        # 첫 mutation도 실제 값을 바꾸도록 별도 지정한다.
        mutations["memo 최대 길이"] = lambda value: value["schemas"]["Memo"].update(
            {"maxLength": 20000}
        )

        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                contract_path = self._contract_path(root)
                contract = self._load_json(contract_path)
                mutate(contract)
                self._write_json(contract_path, contract)

                result = self._run_validator(root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("canonical", result.stdout)

    def test_endpoint_semantics_cannot_be_weakened_or_relabelled(self) -> None:
        mutations = {
            "GET path": lambda value: value["endpoints"][0].update(
                {"path": "/api/v1/saved-places"}
            ),
            "POST auth": lambda value: value["endpoints"][1]["auth"].update(
                {"mode": "optional"}
            ),
            "POST idempotency": lambda value: value["endpoints"][1][
                "idempotency"
            ].update({"required": False}),
            "PATCH header": lambda value: value["endpoints"][2].update(
                {"headersSchema": "CommonHeaders"}
            ),
            "DELETE repeat": lambda value: value["deleteSemantics"].update(
                {"repeat": "204"}
            ),
            "owner concealment": lambda value: value["ownership"].update(
                {"crossOwnerConcealment": "403"}
            ),
            "tag null": lambda value: value["patchSemantics"]["tags"].update(
                {"null": "reject"}
            ),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                contract_path = self._contract_path(root)
                contract = self._load_json(contract_path)
                mutate(contract)
                self._write_json(contract_path, contract)

                result = self._run_validator(root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("canonical", result.stdout)

    def test_catalog_saved_place_entries_are_exact_and_not_ready(self) -> None:
        with self._temporary_repository() as root:
            catalog_path = root / "docs" / "contracts" / "rest" / "catalog.json"
            catalog = self._load_json(catalog_path)
            saved = next(
                item
                for item in catalog["domainContracts"]
                if item["domain"] == "saved-places"
            )
            saved["versions"]["notion"] = "1.0.0"
            saved["readiness"]["metadata"] = {
                "status": "ready",
                "evidence": {"notionPage": "guessed"},
            }
            self._write_json(catalog_path, catalog)

            result = self._run_validator(root)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("catalog canonical", result.stdout)

    def test_fixtures_are_strict_json_without_duplicates_or_non_finite_numbers(self) -> None:
        mutations = {
            "duplicate key": '{"list":{"items":[],"items":[],"page":{"size":20,"hasNext":false,"nextCursor":null}}}',
            "NaN": '{"list":{"items":[],"page":{"size":NaN,"hasNext":false,"nextCursor":null}}}',
            "Infinity": '{"list":{"items":[],"page":{"size":Infinity,"hasNext":false,"nextCursor":null}}}',
        }
        for label, raw in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                fixture = root / "fixtures" / "contracts" / "saved-places" / "success.json"
                fixture.write_text(raw, encoding="utf-8")

                result = self._run_validator(root)

            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("Traceback", result.stdout + result.stderr)

    def test_success_fixture_is_recursively_validated(self) -> None:
        mutations = {
            "unknown property": lambda value: value["list"]["items"][0].update(
                {"token": "secret"}
            ),
            "invalid uri": lambda value: value["list"]["items"][0].update(
                {"thumbnailUrl": "https://example.com/%ZZ"}
            ),
            "invalid date-time": lambda value: value["list"]["items"][0].update(
                {"updatedAt": "2026-08-11T12:00:00"}
            ),
            "wrong integer type": lambda value: value["list"]["items"][0].update(
                {"priority": True}
            ),
            "duplicate tag": lambda value: value["list"]["items"][0].update(
                {"tags": ["동쪽", "동쪽"]}
            ),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                fixture = root / "fixtures" / "contracts" / "saved-places" / "success.json"
                value = self._load_json(fixture)
                mutate(value)
                self._write_json(fixture, value)

                result = self._run_validator(root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("fixture", result.stdout)

    def test_success_fixture_enforces_time_and_cursor_cross_field_invariants(self) -> None:
        mutations = {
            "updated before saved": lambda value: value["list"]["items"][0].update(
                {"updatedAt": "2026-08-03T08:39:59+09:00"}
            ),
            "hasNext without cursor": lambda value: value["list"]["page"].update(
                {"hasNext": True, "nextCursor": None}
            ),
            "terminal page with cursor": lambda value: value["list"]["page"].update(
                {"hasNext": False, "nextCursor": "opaque-next"}
            ),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                fixture = root / "fixtures" / "contracts" / "saved-places" / "success.json"
                value = self._load_json(fixture)
                mutate(value)
                self._write_json(fixture, value)

                result = self._run_validator(root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("semantic", result.stdout)

    def test_problem_fixture_status_code_and_type_are_exact(self) -> None:
        mutations = {
            "status": lambda value: value["409_idempotency_payload_conflict"].update(
                {"status": 500}
            ),
            "code": lambda value: value["409_idempotency_payload_conflict"].update(
                {"code": "ARBITRARY"}
            ),
            "type": lambda value: value["409_idempotency_payload_conflict"].update(
                {"type": "https://api.timing-jeju.com/problems/arbitrary"}
            ),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                fixture = root / "fixtures" / "contracts" / "saved-places" / "problem.json"
                value = self._load_json(fixture)
                mutate(value)
                self._write_json(fixture, value)

                result = self._run_validator(root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("problem fixture", result.stdout)

    def test_request_fixture_matches_method_path_headers_and_presence_rules(self) -> None:
        mutations = {
            "legacy path": lambda value: value["list"].update(
                {"path": "/api/v1/saved-places"}
            ),
            "missing idempotency key": lambda value: value["create"]["headers"].pop(
                "Idempotency-Key"
            ),
            "patch empty body": lambda value: value["patch"].update({"body": {}}),
            "delete body": lambda value: value["delete"].update({"body": {}}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label), self._temporary_repository() as root:
                fixture = root / "fixtures" / "contracts" / "saved-places" / "request.json"
                value = self._load_json(fixture)
                mutate(value)
                self._write_json(fixture, value)

                result = self._run_validator(root)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("request fixture", result.stdout)

    def test_korean_contract_documents_the_schema_drift_without_migration(self) -> None:
        document = (
            REPOSITORY_ROOT
            / "docs"
            / "contracts"
            / "domains"
            / "saved-places"
            / "contract.md"
        ).read_text(encoding="utf-8")

        for phrase in (
            "user_id, place_id",
            "tags text[]",
            "DML RLS",
            "priority 0~5",
            "후속 구현 Issue",
            "Flyway",
            "FastAPI",
            "/api/v1/me/saved-places",
        ):
            self.assertIn(phrase, document)

    @staticmethod
    def _run_validator(root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(VALIDATOR), "--root", str(root)],
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

    def _temporary_repository(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in (
            Path("docs/contracts/domains/saved-places"),
            Path("docs/contracts/rest"),
            Path("fixtures/contracts/saved-places"),
        ):
            source = REPOSITORY_ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(source, target)
        return _TemporaryRepository(temporary, root)

    @staticmethod
    def _contract_path(root: Path) -> Path:
        return root / "docs" / "contracts" / "domains" / "saved-places" / "contract.json"

    @staticmethod
    def _load_json(path: Path):
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def _write_json(path: Path, value) -> None:
        path.write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )


class _TemporaryRepository:
    def __init__(self, temporary: tempfile.TemporaryDirectory, root: Path) -> None:
        self._temporary = temporary
        self._root = root

    def __enter__(self) -> Path:
        return self._root

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self._temporary.cleanup()


if __name__ == "__main__":
    unittest.main()
