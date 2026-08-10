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

    @staticmethod
    def create_ready_evidence(repo_root: Path, domain="profile-legal"):
        paths = {
            "localDocument": Path("docs/contracts/domains") / domain / "contract.md",
            "requestFixture": Path("fixtures/contracts") / domain / "request.json",
            "successFixture": Path("fixtures/contracts") / domain / "success.json",
            "problemFixture": Path("fixtures/contracts") / domain / "problem.json",
            "controller": Path("services/spring-api/src/main/java/com/timingjeju/api/domain")
            / domain.replace("-", "")
            / "controller"
            / "ProfileLegalController.java",
            "openApiTest": Path("services/spring-api/src/test/java/com/timingjeju/api/domain")
            / domain.replace("-", "")
            / "ProfileLegalOpenApiTest.java",
            "contractTest": Path("services/spring-api/src/test/java/com/timingjeju/api/domain")
            / domain.replace("-", "")
            / "ProfileLegalContractTest.java",
        }
        for path in paths.values():
            target = repo_root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("{}" if target.suffix == ".json" else "evidence", encoding="utf-8")
        return {
            "metadata": {
                "status": "ready",
                "evidence": {
                    "localDocument": str(paths["localDocument"]),
                    "notionPage": {
                        "url": "https://www.notion.so/timingjeju/0123456789abcdef0123456789abcdef",
                        "pageId": "0123456789abcdef0123456789abcdef",
                    },
                    "figmaNode": {
                        "url": "https://www.figma.com/design/AbCdEf123456/Profile?node-id=10-20",
                        "fileKey": "AbCdEf123456",
                        "nodeId": "10:20",
                    },
                },
            },
            "example": {
                "status": "ready",
                "evidence": {
                    field: str(paths[field])
                    for field in ("requestFixture", "successFixture", "problemFixture")
                },
            },
            "implementation": {
                "status": "ready",
                "evidence": {
                    field: str(paths[field])
                    for field in ("controller", "openApiTest", "contractTest")
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
        with tempfile.TemporaryDirectory() as directory:
            repo_root = Path(directory)
            catalog = copy.deepcopy(self.catalog)
            first = catalog["domainContracts"][0]
            first["versions"] = {
                "local": catalog["contractVersion"],
                "notion": catalog["contractVersion"],
                "figma": catalog["contractVersion"],
            }
            first["readiness"] = self.create_ready_evidence(repo_root)
            self.assertEqual(
                [], self.validator.validate_catalog(catalog, repo_root=repo_root)
            )

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
                    errors = self.validator.validate_catalog(
                        mutated, repo_root=repo_root
                    )
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

    def test_rejects_unknown_field_in_every_catalog_object(self):
        mutations = {
            "catalog": lambda catalog: catalog.__setitem__("unknown", True),
            "commonRules": lambda catalog: catalog["commonRules"].__setitem__(
                "unknown", True
            ),
            "authorization": lambda catalog: catalog["commonRules"][
                "authorization"
            ].__setitem__("unknown", True),
            "common idempotency": lambda catalog: catalog["commonRules"][
                "idempotency"
            ].__setitem__("unknown", True),
            "cursor": lambda catalog: catalog["commonRules"]["cursor"].__setitem__(
                "unknown", True
            ),
            "problemDetails": lambda catalog: catalog["commonRules"][
                "problemDetails"
            ].__setitem__("unknown", True),
            "asyncRun": lambda catalog: catalog["commonRules"][
                "asyncRun"
            ].__setitem__("unknown", True),
            "fallback": lambda catalog: catalog["commonRules"]["asyncRun"][
                "fallback"
            ].__setitem__("unknown", True),
            "hashes": lambda catalog: catalog["commonRules"]["hashes"].__setitem__(
                "unknown", True
            ),
            "ownership": lambda catalog: catalog["ownership"].__setitem__(
                "unknown", True
            ),
            "domain": lambda catalog: catalog["domainContracts"][0].__setitem__(
                "unknown", True
            ),
            "versions": lambda catalog: catalog["domainContracts"][0][
                "versions"
            ].__setitem__("unknown", True),
            "readiness": lambda catalog: catalog["domainContracts"][0][
                "readiness"
            ].__setitem__("unknown", True),
            "readiness stage": lambda catalog: catalog["domainContracts"][0][
                "readiness"
            ]["metadata"].__setitem__("unknown", True),
        }

        for name, mutation in mutations.items():
            with self.subTest(name=name):
                catalog = copy.deepcopy(self.catalog)
                mutation(catalog)
                errors = self.validator.validate_catalog(catalog)
                self.assertTrue(
                    any("허용되지 않은" in error for error in errors), errors
                )

    def test_rejects_unknown_field_in_every_endpoint_object(self):
        mutations = {
            "endpoint": lambda endpoint: endpoint.__setitem__("unknown", True),
            "auth": lambda endpoint: endpoint["auth"].__setitem__("unknown", True),
            "schemas": lambda endpoint: endpoint["schemas"].__setitem__(
                "unknown", True
            ),
            "responses": lambda endpoint: endpoint["responses"].__setitem__(
                "unknown", True
            ),
            "figma": lambda endpoint: endpoint["figma"].__setitem__("unknown", True),
            "idempotency": lambda endpoint: endpoint["idempotency"].__setitem__(
                "unknown", True
            ),
            "pagination": lambda endpoint: endpoint["pagination"].__setitem__(
                "unknown", True
            ),
            "pagination size": lambda endpoint: endpoint["pagination"][
                "size"
            ].__setitem__("unknown", True),
        }

        for name, mutation in mutations.items():
            with self.subTest(name=name):
                catalog = copy.deepcopy(self.catalog)
                endpoint = self.endpoint()
                mutation(endpoint)
                catalog["endpoints"] = [endpoint]
                errors = self.validator.validate_catalog(catalog)
                self.assertTrue(
                    any("허용되지 않은" in error for error in errors), errors
                )

    def test_rejects_unknown_field_in_every_template_object(self):
        mutations = {
            "template": lambda template: template.__setitem__("unknown", True),
            "defaults": lambda template: template["defaults"].__setitem__(
                "unknown", True
            ),
            "default auth": lambda template: template["defaults"]["auth"].__setitem__(
                "unknown", True
            ),
            "default idempotency": lambda template: template["defaults"][
                "idempotency"
            ].__setitem__("unknown", True),
            "default pagination": lambda template: template["defaults"][
                "pagination"
            ].__setitem__("unknown", True),
            "template endpoint": lambda template: template["endpoint"].__setitem__(
                "unknown", True
            ),
            "template endpoint auth": lambda template: template["endpoint"][
                "auth"
            ].__setitem__("unknown", True),
            "template endpoint schemas": lambda template: template["endpoint"][
                "schemas"
            ].__setitem__("unknown", True),
            "template endpoint responses": lambda template: template["endpoint"][
                "responses"
            ].__setitem__("unknown", True),
            "template endpoint figma": lambda template: template["endpoint"][
                "figma"
            ].__setitem__("unknown", True),
            "template endpoint idempotency": lambda template: template["endpoint"][
                "idempotency"
            ].__setitem__("unknown", True),
            "template endpoint pagination": lambda template: template["endpoint"][
                "pagination"
            ].__setitem__("unknown", True),
        }

        for name, mutation in mutations.items():
            with self.subTest(name=name):
                template = copy.deepcopy(self.template)
                mutation(template)
                errors = self.validate_files(self.catalog, template)
                self.assertTrue(
                    any("허용되지 않은" in error for error in errors), errors
                )

    def test_rejects_unknown_field_in_structured_readiness_evidence(self):
        for stage in ("metadata", "example", "implementation"):
            with self.subTest(stage=stage):
                catalog = copy.deepcopy(self.catalog)
                first = catalog["domainContracts"][0]
                first["versions"] = {
                    "local": catalog["contractVersion"],
                    "notion": catalog["contractVersion"],
                    "figma": catalog["contractVersion"],
                }
                first["readiness"] = self.ready_readiness()
                first["readiness"][stage]["evidence"]["unknown"] = True
                errors = self.validator.validate_catalog(catalog)
                self.assertTrue(
                    any("허용되지 않은" in error for error in errors), errors
                )

    def test_coordinated_contract_version_change_cannot_bypass_canonical_version(self):
        catalog = copy.deepcopy(self.catalog)
        template = copy.deepcopy(self.template)
        catalog["contractVersion"] = "9.9.9"
        template["contractVersion"] = "9.9.9"
        template["endpoint"]["contractVersion"] = "9.9.9"
        for domain in catalog["domainContracts"]:
            domain["versions"]["local"] = "9.9.9"

        errors = self.validate_files(catalog, template)
        self.assertTrue(
            any("지원하는 canonical contractVersion" in error for error in errors),
            errors,
        )

    def test_cursor_size_rejects_boolean_and_out_of_range_values(self):
        invalid_sizes = (
            {"default": True, "max": 100},
            {"default": 1, "max": True},
            {"default": -1, "max": 100},
            {"default": 0, "max": 100},
            {"default": 1, "max": -1},
            {"default": 1, "max": 0},
            {"default": 1, "max": 101},
        )
        for size in invalid_sizes:
            with self.subTest(size=size):
                catalog = copy.deepcopy(self.catalog)
                endpoint = self.endpoint()
                endpoint["pagination"]["size"] = size
                catalog["endpoints"] = [endpoint]
                self.assertTrue(self.validator.validate_catalog(catalog))

    def test_cursor_size_accepts_1_50_100_boundaries(self):
        for size in (
            {"default": 1, "max": 50},
            {"default": 50, "max": 100},
            {"default": 100, "max": 100},
        ):
            with self.subTest(size=size):
                catalog = copy.deepcopy(self.catalog)
                endpoint = self.endpoint()
                endpoint["pagination"]["size"] = size
                catalog["endpoints"] = [endpoint]
                self.assertEqual([], self.validator.validate_catalog(catalog))

    def test_rejects_dot_segments_and_detects_canonical_path_duplicates(self):
        for path in ("/api/v1/../admin", "/api/v1/./resources"):
            with self.subTest(path=path):
                catalog = copy.deepcopy(self.catalog)
                catalog["endpoints"] = [self.endpoint(path=path)]
                errors = self.validator.validate_catalog(catalog)
                self.assertTrue(any("dot segment" in error for error in errors), errors)

        catalog = copy.deepcopy(self.catalog)
        catalog["endpoints"] = [
            self.endpoint(path="/api/v1/resources"),
            self.endpoint(path="/api/v1/./resources"),
        ]
        errors = self.validator.validate_catalog(catalog)
        self.assertTrue(any("canonical method/path 중복" in error for error in errors), errors)

    def test_ready_evidence_requires_real_owned_repository_files(self):
        with tempfile.TemporaryDirectory() as directory:
            repo_root = Path(directory) / "repo"
            repo_root.mkdir()
            catalog = copy.deepcopy(self.catalog)
            first = catalog["domainContracts"][0]
            first["versions"] = {
                "local": catalog["contractVersion"],
                "notion": catalog["contractVersion"],
                "figma": catalog["contractVersion"],
            }
            first["readiness"] = self.create_ready_evidence(repo_root)

            invalid_paths = {
                "missing local document": (
                    "metadata",
                    "localDocument",
                    "docs/contracts/domains/profile-legal/missing.md",
                ),
                "path traversal": ("metadata", "localDocument", "../outside.md"),
                "wrong fixture extension": (
                    "example",
                    "requestFixture",
                    "docs/contracts/domains/profile-legal/contract.md",
                ),
                "controller outside owned source": (
                    "implementation",
                    "controller",
                    "services/spring-api/src/test/java/com/timingjeju/api/domain/profilelegal/ProfileLegalOpenApiTest.java",
                ),
            }
            for name, (stage, field, value) in invalid_paths.items():
                with self.subTest(name=name):
                    mutated = copy.deepcopy(catalog)
                    mutated["domainContracts"][0]["readiness"][stage]["evidence"][
                        field
                    ] = value
                    errors = self.validator.validate_catalog(
                        mutated, repo_root=repo_root
                    )
                    self.assertTrue(any("evidence 경로" in error for error in errors), errors)

            outside = Path(directory) / "outside.md"
            outside.write_text("outside", encoding="utf-8")
            symlink = repo_root / "docs/contracts/domains/profile-legal/link.md"
            symlink.symlink_to(outside)
            first["readiness"]["metadata"]["evidence"]["localDocument"] = str(
                symlink.relative_to(repo_root)
            )
            errors = self.validator.validate_catalog(catalog, repo_root=repo_root)
            self.assertTrue(any("symlink" in error or "저장소 밖" in error for error in errors), errors)

    def test_ready_metadata_requires_verifiable_notion_and_figma_linkage(self):
        with tempfile.TemporaryDirectory() as directory:
            repo_root = Path(directory)
            catalog = copy.deepcopy(self.catalog)
            first = catalog["domainContracts"][0]
            first["versions"] = {
                "local": catalog["contractVersion"],
                "notion": catalog["contractVersion"],
                "figma": catalog["contractVersion"],
            }
            first["readiness"] = self.create_ready_evidence(repo_root)
            mutations = {
                "fake strings": lambda evidence: evidence.update(
                    {"notionPage": "truthy", "figmaNode": "truthy"}
                ),
                "notion id mismatch": lambda evidence: evidence["notionPage"].update(
                    {"pageId": "ffffffffffffffffffffffffffffffff"}
                ),
                "figma node mismatch": lambda evidence: evidence["figmaNode"].update(
                    {"nodeId": "99:99"}
                ),
            }
            for name, mutation in mutations.items():
                with self.subTest(name=name):
                    mutated = copy.deepcopy(catalog)
                    mutation(
                        mutated["domainContracts"][0]["readiness"]["metadata"][
                            "evidence"
                        ]
                    )
                    errors = self.validator.validate_catalog(
                        mutated, repo_root=repo_root
                    )
                    self.assertTrue(any("linkage" in error for error in errors), errors)

    def test_json_loader_rejects_duplicate_keys_at_every_depth_without_traceback(self):
        catalog_text = CATALOG_PATH.read_text(encoding="utf-8")
        template_text = TEMPLATE_PATH.read_text(encoding="utf-8")
        cases = {
            "catalog top": (
                catalog_text.replace(
                    '"contractVersion": "1.0.0",',
                    '"contractVersion": "1.0.0", "contractVersion": "9.9.9",',
                    1,
                ),
                template_text,
            ),
            "catalog nested": (
                catalog_text.replace(
                    '"cursor": "opaque",',
                    '"cursor": "opaque", "cursor": "transparent",',
                    1,
                ),
                template_text,
            ),
            "template top": (
                catalog_text,
                template_text.replace(
                    '"templateId": "timing-jeju-rest-contract/v1",',
                    '"templateId": "timing-jeju-rest-contract/v1", "templateId": "other",',
                    1,
                ),
            ),
            "template nested": (
                catalog_text,
                template_text.replace(
                    '"pagination": {"type": "none"}',
                    '"pagination": {"type": "none", "type": "cursor"}',
                    1,
                ),
            ),
        }
        for name, (raw_catalog, raw_template) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                catalog_path = Path(directory) / "catalog.json"
                template_path = Path(directory) / "template.json"
                catalog_path.write_text(raw_catalog, encoding="utf-8")
                template_path.write_text(raw_template, encoding="utf-8")
                errors = self.validator.validate_contract_files(
                    catalog_path, template_path
                )
                self.assertTrue(any("중복 JSON 키" in error for error in errors), errors)
                result = subprocess.run(
                    [
                        "python3",
                        str(VALIDATOR_PATH),
                        str(catalog_path),
                        "--template",
                        str(template_path),
                    ],
                    cwd=ROOT,
                    capture_output=True,
                    check=False,
                    text=True,
                )
                self.assertEqual(1, result.returncode)
                self.assertIn("중복 JSON 키", result.stderr)
                self.assertNotIn("Traceback", result.stderr)

    def test_rejects_strict_json_type_and_unique_array_bypasses(self):
        mutations = {
            "blank resource hiding": lambda catalog: catalog["commonRules"][
                "authorization"
            ].__setitem__("resourceHiding", " "),
            "wrong resource hiding": lambda catalog: catalog["commonRules"][
                "authorization"
            ].__setitem__("resourceHiding", "always 200"),
            "duplicate forbidden field": lambda catalog: catalog["commonRules"][
                "problemDetails"
            ]["forbiddenFields"].append("message"),
            "response bool": lambda catalog: catalog["endpoints"].append(
                {**self.endpoint(), "responses": {"success": [True], "errors": [400]}}
            ),
            "response float": lambda catalog: catalog["endpoints"].append(
                {**self.endpoint(), "responses": {"success": [200.0], "errors": [400]}}
            ),
            "duplicate response": lambda catalog: catalog["endpoints"].append(
                {**self.endpoint(), "responses": {"success": [200, 200], "errors": [400]}}
            ),
            "ownership float": lambda catalog: catalog["ownership"].__setitem__(
                "durableCommandSchema", 108.0
            ),
            "ownership bool": lambda catalog: catalog["ownership"].__setitem__(
                "workerRuntime", True
            ),
            "ownership null": lambda catalog: catalog["ownership"].__setitem__(
                "locationCleanup", None
            ),
            "domain issue float": lambda catalog: catalog["domainContracts"][
                0
            ].__setitem__("issue", 82.0),
            "domain issue bool": lambda catalog: catalog["domainContracts"][
                0
            ].__setitem__("issue", True),
            "auth status float": lambda catalog: catalog["endpoints"].append(
                {
                    **self.endpoint(),
                    "auth": {"mode": "required", "missingToken": 401.0, "invalidToken": 401.0},
                }
            ),
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                catalog = copy.deepcopy(self.catalog)
                mutation(catalog)
                self.assertTrue(self.validator.validate_catalog(catalog))

    def test_route_identity_canonicalizes_placeholder_names_only(self):
        duplicate_pairs = (
            (
                "/api/v1/resources/{id}",
                "/api/v1/resources/{resourceId}",
            ),
            (
                "/api/v1/trips/{tripId}/days/{dayId}",
                "/api/v1/trips/{id}/days/{resourceId}",
            ),
        )
        for first_path, second_path in duplicate_pairs:
            with self.subTest(first=first_path, second=second_path):
                catalog = copy.deepcopy(self.catalog)
                catalog["endpoints"] = [
                    self.endpoint(path=first_path),
                    self.endpoint(path=second_path),
                ]
                errors = self.validator.validate_catalog(catalog)
                self.assertTrue(
                    any("canonical method/path 중복" in error for error in errors),
                    errors,
                )

        catalog = copy.deepcopy(self.catalog)
        catalog["endpoints"] = [
            self.endpoint(path="/api/v1/resources/static"),
            self.endpoint(path="/api/v1/resources/{id}"),
        ]
        self.assertEqual([], self.validator.validate_catalog(catalog))

    def test_list_operation_requires_exact_canonical_cursor_pagination(self):
        catalog = copy.deepcopy(self.catalog)
        endpoint = self.endpoint(operation="list")
        endpoint["pagination"] = {"type": "none"}
        catalog["endpoints"] = [endpoint]
        errors = self.validator.validate_catalog(catalog)
        self.assertTrue(any("list operation" in error for error in errors), errors)

        catalog = copy.deepcopy(self.catalog)
        endpoint = self.endpoint(operation="list")
        catalog["commonRules"]["cursor"]["cursor"] = "transparent"
        endpoint["pagination"]["cursor"] = "transparent"
        catalog["endpoints"] = [endpoint]
        errors = self.validator.validate_catalog(catalog)
        self.assertTrue(any("canonical cursor" in error for error in errors), errors)

    def test_domain_issue_mapping_and_domain_names_are_canonical_and_unique(self):
        catalog = copy.deepcopy(self.catalog)
        first, second = catalog["domainContracts"][:2]
        first["issue"], second["issue"] = second["issue"], first["issue"]
        errors = self.validator.validate_catalog(catalog)
        self.assertTrue(any("canonical issue/domain mapping" in error for error in errors), errors)

        catalog = copy.deepcopy(self.catalog)
        catalog["domainContracts"][1]["domain"] = catalog["domainContracts"][0][
            "domain"
        ]
        errors = self.validator.validate_catalog(catalog)
        self.assertTrue(any("domain 중복" in error for error in errors), errors)

    def test_evidence_symlink_target_must_keep_stage_domain_and_file_kind(self):
        with tempfile.TemporaryDirectory() as directory:
            repo_root = Path(directory)
            catalog = copy.deepcopy(self.catalog)
            first = catalog["domainContracts"][0]
            first["versions"] = {
                "local": catalog["contractVersion"],
                "notion": catalog["contractVersion"],
                "figma": catalog["contractVersion"],
            }
            first["readiness"] = self.create_ready_evidence(repo_root)
            link = repo_root / "docs/contracts/domains/profile-legal/link.md"
            original = first["readiness"]["metadata"]["evidence"]["localDocument"]

            cross_domain = repo_root / "docs/contracts/domains/places/contract.md"
            cross_domain.parent.mkdir(parents=True, exist_ok=True)
            cross_domain.write_text("other domain", encoding="utf-8")
            link.symlink_to(cross_domain)
            first["readiness"]["metadata"]["evidence"]["localDocument"] = str(
                link.relative_to(repo_root)
            )
            errors = self.validator.validate_catalog(catalog, repo_root=repo_root)
            self.assertTrue(any("resolve된 evidence" in error for error in errors), errors)

            link.unlink()
            wrong_type = repo_root / "docs/contracts/domains/profile-legal/data.json"
            wrong_type.write_text("{}", encoding="utf-8")
            link.symlink_to(wrong_type)
            errors = self.validator.validate_catalog(catalog, repo_root=repo_root)
            self.assertTrue(any("resolve된 evidence" in error for error in errors), errors)

            link.unlink()
            outside = Path(directory).with_name(f"{Path(directory).name}-outside.md")
            outside.write_text("outside", encoding="utf-8")
            try:
                link.symlink_to(outside)
                errors = self.validator.validate_catalog(catalog, repo_root=repo_root)
                self.assertTrue(
                    any("symlink" in error or "저장소 밖" in error for error in errors),
                    errors,
                )
            finally:
                outside.unlink(missing_ok=True)
            first["readiness"]["metadata"]["evidence"]["localDocument"] = original

    def test_figma_link_requires_exact_route_file_key_and_single_node_id(self):
        with tempfile.TemporaryDirectory() as directory:
            repo_root = Path(directory)
            catalog = copy.deepcopy(self.catalog)
            first = catalog["domainContracts"][0]
            first["versions"] = {
                "local": catalog["contractVersion"],
                "notion": catalog["contractVersion"],
                "figma": catalog["contractVersion"],
            }
            first["readiness"] = self.create_ready_evidence(repo_root)
            mutations = {
                "evil route": {
                    "url": "https://www.figma.com/evil/AbCdEf123456/Profile?node-id=10-20",
                },
                "slash file key": {
                    "url": "https://www.figma.com/design/Ab/Cd/Profile?node-id=10-20",
                    "fileKey": "Ab/Cd",
                },
                "duplicate node id": {
                    "url": "https://www.figma.com/file/AbCdEf123456/Profile?node-id=10-20&node-id=10-20",
                },
                "extra query": {
                    "url": "https://www.figma.com/file/AbCdEf123456/Profile?node-id=10-20&t=abc",
                },
                "node mismatch": {"nodeId": "99:99"},
            }
            for name, updates in mutations.items():
                with self.subTest(name=name):
                    mutated = copy.deepcopy(catalog)
                    linkage = mutated["domainContracts"][0]["readiness"]["metadata"][
                        "evidence"
                    ]["figmaNode"]
                    linkage.update(updates)
                    errors = self.validator.validate_catalog(
                        mutated, repo_root=repo_root
                    )
                    self.assertTrue(any("Figma linkage" in error for error in errors), errors)

    def test_response_status_sets_are_unique_disjoint_and_strict_integers(self):
        response_cases = (
            {"success": [200], "errors": [200, 400]},
            {"success": [200, 200], "errors": [400]},
            {"success": [200], "errors": [400, 400]},
            {"success": [True], "errors": [400]},
            {"success": [200.0], "errors": [400]},
            {"success": ["200"], "errors": [400]},
        )
        for responses in response_cases:
            with self.subTest(responses=responses):
                catalog = copy.deepcopy(self.catalog)
                endpoint = self.endpoint()
                endpoint["responses"] = responses
                catalog["endpoints"] = [endpoint]
                self.assertTrue(self.validator.validate_catalog(catalog))

    def test_quality_gate_executes_rest_contract_validator(self):
        quality_gate = (ROOT / "scripts" / "quality-gate.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("python3 scripts/validate_rest_contracts.py", quality_gate)


if __name__ == "__main__":
    unittest.main()
