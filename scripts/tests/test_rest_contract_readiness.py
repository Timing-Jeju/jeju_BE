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
TEMPLATE_PATH = ROOT / "docs" / "contracts" / "rest" / "endpoint-template.json"


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
        cls.template = json.loads(TEMPLATE_PATH.read_text(encoding="utf-8"))

    def validate(self, mutate=None):
        catalog = copy.deepcopy(self.catalog)
        if mutate is not None:
            mutate(catalog)
        return self.validator.validate_catalog(catalog)

    @staticmethod
    def endpoint(method="GET", path="/api/v1/resources", operation="read"):
        return {
            "method": method,
            "path": path,
            "operation": operation,
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

    @staticmethod
    def required_idempotency():
        return {
            "required": True,
            "header": "Idempotency-Key",
            "scope": "ownerSub + method + normalizedPath + key",
            "ttl": "endpoint-defined",
            "replay": "same scope and payload replays stored response",
            "payloadConflict": "409 IDEMPOTENCY_KEY_REUSED",
            "concurrentRequest": "409 IDEMPOTENCY_REQUEST_IN_PROGRESS",
        }

    @staticmethod
    def structured_readiness(status="not-ready"):
        return {
            "metadata": {"status": status, "evidence": None},
            "example": {"status": status, "evidence": None},
            "implementation": {"status": status, "evidence": None},
        }

    @staticmethod
    def ready_readiness():
        return {
            "metadata": {
                "status": "ready",
                "evidence": {
                    "localDocument": "docs/contracts/domain.md",
                    "notionPage": "notion-page-id",
                    "figmaNode": "figma-node-id",
                },
            },
            "example": {
                "status": "ready",
                "evidence": {
                    "requestFixture": "fixtures/request.json",
                    "successFixture": "fixtures/success.json",
                    "problemFixture": "fixtures/problem.json",
                },
            },
            "implementation": {
                "status": "ready",
                "evidence": {
                    "controller": "ResourceController.java",
                    "openApiTest": "OpenApiDocumentationTest.java",
                    "contractTest": "ResourceContractTest.java",
                },
            },
        }

    def validate_files(self, catalog, template):
        with tempfile.TemporaryDirectory() as directory:
            catalog_path = Path(directory) / "catalog.json"
            template_path = Path(directory) / "endpoint-template.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            template_path.write_text(json.dumps(template), encoding="utf-8")
            return self.validator.validate_contract_files(catalog_path, template_path)

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

    def test_rejects_each_common_exact_semantic_drift_independently(self):
        mutations = {
            "principal": lambda rules: rules["authorization"].__setitem__(
                "principal", "user_metadata.sub"
            ),
            "missing token code": lambda rules: rules["authorization"].__setitem__(
                "missingTokenCode", "OTHER"
            ),
            "invalid token code": lambda rules: rules["authorization"].__setitem__(
                "invalidTokenCode", "OTHER"
            ),
            "required operations": lambda rules: rules["idempotency"].__setitem__(
                "requiredFor", ["create", "compute"]
            ),
            "problem media type": lambda rules: rules["problemDetails"].__setitem__(
                "mediaType", "application/json"
            ),
            "worker input": lambda rules: rules["asyncRun"].__setitem__(
                "workerInput", "mutable request"
            ),
            "failure object": lambda rules: rules["asyncRun"].__setitem__(
                "failureObjectFields", ["code"]
            ),
        }

        for name, mutation in mutations.items():
            with self.subTest(name=name):
                catalog = copy.deepcopy(self.catalog)
                mutation(catalog["commonRules"])
                self.assertTrue(self.validator.validate_catalog(catalog))

    def test_rejects_endpoint_blank_type_schema_method_and_path_bypasses(self):
        mutations = {
            "blank owner": lambda endpoint: endpoint.__setitem__("owner", "   "),
            "wrong responses type": lambda endpoint: endpoint.__setitem__(
                "responses", []
            ),
            "blank schema": lambda endpoint: endpoint["schemas"].__setitem__(
                "query", " "
            ),
            "unsupported method": lambda endpoint: endpoint.__setitem__(
                "method", "TRACE"
            ),
            "malformed path": lambda endpoint: endpoint.__setitem__(
                "path", "api/v1/resources"
            ),
            "unknown operation": lambda endpoint: endpoint.__setitem__(
                "operation", "side-effect"
            ),
        }

        for name, mutation in mutations.items():
            with self.subTest(name=name):
                catalog = copy.deepcopy(self.catalog)
                endpoint = self.endpoint()
                endpoint["pagination"] = {"type": "none"}
                mutation(endpoint)
                catalog["endpoints"] = [endpoint]
                self.assertTrue(self.validator.validate_catalog(catalog))

    def test_create_compute_apply_cannot_bypass_idempotency_contract(self):
        for operation in ("create", "compute", "apply"):
            with self.subTest(operation=operation, bypass="required false"):
                catalog = copy.deepcopy(self.catalog)
                endpoint = self.endpoint(
                    "POST", f"/api/v1/resources/{operation}", operation
                )
                endpoint["pagination"] = {"type": "none"}
                endpoint["idempotency"] = {"required": False, "header": "none"}
                catalog["endpoints"] = [endpoint]
                self.assertTrue(self.validator.validate_catalog(catalog))

            for missing_field in (
                "scope",
                "ttl",
                "replay",
                "payloadConflict",
                "concurrentRequest",
            ):
                with self.subTest(operation=operation, missing=missing_field):
                    catalog = copy.deepcopy(self.catalog)
                    endpoint = self.endpoint(
                        "POST", f"/api/v1/resources/{operation}", operation
                    )
                    endpoint["pagination"] = {"type": "none"}
                    endpoint["idempotency"] = self.required_idempotency()
                    endpoint["idempotency"].pop(missing_field)
                    catalog["endpoints"] = [endpoint]
                    self.assertTrue(self.validator.validate_catalog(catalog))

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

    def test_repository_template_and_catalog_are_validated_together(self):
        self.assertEqual(
            [], self.validator.validate_contract_files(CATALOG_PATH, TEMPLATE_PATH)
        )

    def test_rejects_missing_template_and_template_metadata_drift(self):
        missing_path = TEMPLATE_PATH.with_name("missing-template.json")
        errors = self.validator.validate_contract_files(CATALOG_PATH, missing_path)
        self.assertTrue(any("template" in error for error in errors))

        mutations = {
            "catalog version missing": lambda template: template.pop(
                "catalogVersion"
            ),
            "catalog version": lambda template: template.__setitem__(
                "catalogVersion", "other/v1"
            ),
            "contract version missing": lambda template: template.pop(
                "contractVersion"
            ),
            "contract version": lambda template: template.__setitem__(
                "contractVersion", "9.9.9"
            ),
            "required field": lambda template: template["requiredEndpointFields"].remove(
                "owner"
            ),
            "endpoint field missing": lambda template: template["endpoint"].pop(
                "owner"
            ),
            "defaults missing": lambda template: template.pop("defaults"),
            "default inheritance": lambda template: template["defaults"][
                "idempotency"
            ].__setitem__("header", "X-Idempotency"),
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                template = copy.deepcopy(self.template)
                template.setdefault(
                    "requiredEndpointFields", sorted(self.endpoint().keys())
                )
                template.setdefault(
                    "defaults",
                    {
                        "idempotency": {"required": False, "header": "none"},
                        "pagination": {"type": "none"},
                    },
                )
                mutation(template)
                self.assertTrue(self.validate_files(self.catalog, template))

    def test_rejects_duplicate_domain_issue_even_when_issue_set_is_complete(self):
        catalog = copy.deepcopy(self.catalog)
        catalog["domainContracts"].append(copy.deepcopy(catalog["domainContracts"][0]))

        errors = self.validator.validate_catalog(catalog)
        self.assertTrue(any("중복" in error and "#82" in error for error in errors))

    def test_rejects_unstructured_or_out_of_order_readiness_evidence(self):
        catalog = copy.deepcopy(self.catalog)
        for domain in catalog["domainContracts"]:
            domain["readiness"] = self.structured_readiness()
        first = catalog["domainContracts"][0]
        first["readiness"]["implementation"] = {
            "status": "ready",
            "evidence": "controller exists",
        }

        errors = self.validator.validate_catalog(catalog)
        self.assertTrue(any("구조화" in error for error in errors))
        self.assertTrue(any("선행" in error for error in errors))

    def test_not_linked_sources_cannot_be_promoted_to_any_ready_stage(self):
        for stage in ("metadata", "example", "implementation"):
            with self.subTest(stage=stage):
                catalog = copy.deepcopy(self.catalog)
                for domain in catalog["domainContracts"]:
                    domain["readiness"] = self.structured_readiness()
                first = catalog["domainContracts"][0]
                first["readiness"][stage] = {
                    "status": "ready",
                    "evidence": {
                        "controller": "Controller.java",
                        "openApiTest": "OpenApiTest.java",
                        "contractTest": "ContractTest.java",
                    },
                }

                errors = self.validator.validate_catalog(catalog)
                self.assertTrue(any("not-linked" in error for error in errors))

    def test_ready_domain_requires_exact_structured_evidence_for_every_stage(self):
        catalog = copy.deepcopy(self.catalog)
        first = catalog["domainContracts"][0]
        first["versions"] = {
            "local": catalog["contractVersion"],
            "notion": catalog["contractVersion"],
            "figma": catalog["contractVersion"],
        }
        first["readiness"] = self.ready_readiness()
        self.assertEqual([], self.validator.validate_catalog(catalog))

        for stage, field in (
            ("metadata", "notionPage"),
            ("example", "problemFixture"),
            ("implementation", "controller"),
            ("implementation", "openApiTest"),
            ("implementation", "contractTest"),
        ):
            with self.subTest(stage=stage, field=field):
                mutated = copy.deepcopy(catalog)
                mutated["domainContracts"][0]["readiness"][stage]["evidence"].pop(
                    field
                )
                errors = self.validator.validate_catalog(mutated)
                self.assertTrue(any("evidence" in error for error in errors))

    def test_malformed_nested_types_return_errors_without_traceback(self):
        mutations = (
            lambda catalog: catalog["commonRules"].__setitem__("authorization", []),
            lambda catalog: catalog["commonRules"]["problemDetails"].__setitem__(
                "fields", [{}]
            ),
            lambda catalog: catalog["endpoints"].append(
                {**self.endpoint(), "method": []}
            ),
            lambda catalog: catalog["domainContracts"][0].__setitem__(
                "issue", {"invalid": 82}
            ),
            lambda catalog: catalog["domainContracts"][0]["readiness"][
                "metadata"
            ].__setitem__("status", {}),
        )

        for mutation in mutations:
            with self.subTest(mutation=mutation):
                catalog = copy.deepcopy(self.catalog)
                mutation(catalog)
                self.assertTrue(self.validator.validate_catalog(catalog))

    def test_quality_gate_executes_rest_contract_validator(self):
        quality_gate = (ROOT / "scripts" / "quality-gate.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("python3 scripts/validate_rest_contracts.py", quality_gate)


if __name__ == "__main__":
    unittest.main()
