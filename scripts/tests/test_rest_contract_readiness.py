from __future__ import annotations

import copy
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR_PATH = ROOT / "scripts" / "validate_rest_contracts.py"
CATALOG_PATH = ROOT / "docs" / "contracts" / "rest" / "catalog.json"


def load_validator():
    spec = importlib.util.spec_from_file_location("validate_rest_contracts", VALIDATOR_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("REST 계약 검사기를 불러올 수 없습니다.")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RestContractReadinessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validator = load_validator()
        cls.catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))

    def validate(self, mutate=None):
        catalog = copy.deepcopy(self.catalog)
        if mutate is not None:
            mutate(catalog)
        return self.validator.validate_catalog(catalog)

    @staticmethod
    def endpoint(method="GET", path="/api/v1/resources"):
        return {
            "method": method,
            "path": path,
            "auth": {
                "mode": "required",
                "missingToken": 401,
                "invalidToken": 401,
            },
            "owner": "resource",
            "schemas": {
                "path": "none",
                "query": "ResourceQuery",
                "headers": "CommonHeaders",
                "body": "none",
            },
            "presence": "required/optional/null/omitted 명시",
            "responses": {"success": [200], "errors": [400, 401]},
            "dbOwner": "후속 도메인 Issue",
            "requestTimeCall": "none",
            "dataLineage": "normalized-read-model",
            "figma": {
                "node": "node-id",
                "action": "목록 조회",
                "loading": "정의",
                "empty": "정의",
                "error": "정의",
            },
            "contractVersion": "1.0.0",
            "idempotency": {"required": False, "header": "none"},
            "pagination": {
                "type": "cursor",
                "cursor": "opaque",
                "size": {"default": 20, "max": 100},
                "stableSort": "createdAt desc, id desc",
                "tieBreaker": "id",
            },
        }

    def test_repository_catalog_is_ready(self):
        self.assertEqual([], self.validate())

    def test_cli_reports_errors_in_korean(self):
        catalog = copy.deepcopy(self.catalog)
        catalog["contractVersion"] = ""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run(
                ["python3", str(VALIDATOR_PATH), str(path)],
                capture_output=True,
                check=False,
                text=True,
            )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("계약 버전", result.stderr)

    def test_rejects_missing_required_contract_field_and_duplicate_endpoint(self):
        def mutate(catalog):
            first = self.endpoint()
            catalog["endpoints"] = [first, copy.deepcopy(first)]
            first.pop("owner")

        errors = self.validate(mutate)
        self.assertTrue(any("owner" in error for error in errors))
        self.assertTrue(any("method/path 중복" in error for error in errors))

    def test_rejects_auth_idempotency_and_cursor_inheritance_drift(self):
        def mutate(catalog):
            get_endpoint = self.endpoint()
            post_endpoint = self.endpoint("POST", "/api/v1/resources/runs")
            post_endpoint["idempotency"] = {"required": True, "header": ""}
            post_endpoint["pagination"] = {"type": "none"}
            catalog["endpoints"] = [get_endpoint, post_endpoint]
            get_endpoint["auth"]["mode"] = "anonymous"
            get_endpoint["pagination"]["tieBreaker"] = ""

        errors = self.validate(mutate)
        self.assertTrue(any("인증 mode" in error for error in errors))
        self.assertTrue(any("Idempotency-Key" in error for error in errors))
        self.assertTrue(any("tie-breaker" in error for error in errors))

    def test_optional_auth_allows_missing_token_but_rejects_invalid_token(self):
        def mutate(catalog):
            endpoint = self.endpoint()
            endpoint["auth"] = {
                "mode": "optional",
                "missingToken": "anonymous",
                "invalidToken": 200,
            }
            catalog["endpoints"] = [endpoint]

        errors = self.validate(mutate)
        self.assertFalse(any("token 없음" in error for error in errors))
        self.assertTrue(any("invalid token" in error for error in errors))

    def test_rejects_common_idempotency_and_cursor_policy_drift(self):
        def mutate(catalog):
            catalog["commonRules"]["idempotency"]["header"] = "X-Idempotency"
            catalog["commonRules"]["cursor"]["requires"].remove("stableSort")

        errors = self.validate(mutate)
        self.assertTrue(any("Idempotency-Key" in error for error in errors))
        self.assertTrue(any("stable sort" in error for error in errors))

    def test_rejects_problem_details_legacy_fields(self):
        def mutate(catalog):
            catalog["commonRules"]["problemDetails"]["fields"].append("message")
            catalog["commonRules"]["problemDetails"]["forbiddenFields"].remove(
                "violations"
            )

        errors = self.validate(mutate)
        self.assertTrue(any("Problem Details" in error for error in errors))
        self.assertTrue(any("message" in error for error in errors))
        self.assertTrue(any("violations" in error for error in errors))

    def test_rejects_async_state_fallback_and_candidate_expiry_drift(self):
        def mutate(catalog):
            async_rules = catalog["commonRules"]["asyncRun"]
            async_rules["states"].append("expired")
            async_rules["fallback"]["status"] = "fallback"
            async_rules["candidateExpiryField"] = "status"

        errors = self.validate(mutate)
        self.assertTrue(any("canonical 상태" in error for error in errors))
        self.assertTrue(any("fallback" in error for error in errors))
        self.assertTrue(any("expiresAt" in error for error in errors))

    def test_rejects_hash_and_downstream_owner_scope_drift(self):
        def mutate(catalog):
            catalog["commonRules"]["hashes"]["mcpInputHash"] = "commandInputHash"
            catalog["ownership"]["durableCommandSchema"] = 72
            catalog["ownership"]["locationCleanup"] = 72
            catalog["ownership"]["workerRuntime"] = 72

        errors = self.validate(mutate)
        self.assertTrue(any("hash" in error for error in errors))
        for issue in (108, 109, 74):
            self.assertTrue(any(f"#{issue}" in error for error in errors))

    def test_rejects_domain_inheritance_and_readiness_version_drift(self):
        def mutate(catalog):
            catalog["domainContracts"].pop()
            first = catalog["domainContracts"][0]
            first["inherits"] = "다른-template"
            first["versions"]["notion"] = "v0"
            first["readiness"]["implementation"] = "ready"

        errors = self.validate(mutate)
        self.assertTrue(any("#94" in error for error in errors))
        self.assertTrue(any("명시적으로 상속" in error for error in errors))
        self.assertTrue(any("Notion/Figma/local" in error for error in errors))
        self.assertTrue(any("Implementation Ready" in error for error in errors))

    def test_quality_gate_executes_rest_contract_validator(self):
        quality_gate = (ROOT / "scripts" / "quality-gate.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("python3 scripts/validate_rest_contracts.py", quality_gate)


if __name__ == "__main__":
    unittest.main()
