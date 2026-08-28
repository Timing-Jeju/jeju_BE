import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.validate_openapi_frontend_readiness import Validator


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts" / "validate_openapi_frontend_readiness.py"


def valid_document():
    problem_example = {
        "type": "https://api.timing-jeju.com/problems/invalid-request",
        "title": "요청 오류",
        "status": 400,
        "detail": "입력값을 확인해 주세요.",
        "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
        "code": "INVALID_REQUEST",
        "traceId": "0123456789abcdef0123456789abcdef",
        "fieldErrors": [],
    }
    document = {
        "openapi": "3.1.0",
        "info": {"title": "Test", "version": "v1"},
        "security": [{"bearerAuth": []}],
        "paths": {
            "/api/v1/me": {
                "patch": {
                    "operationId": "widgetsCreate",
                    "tags": ["위젯"],
                    "summary": "위젯 생성",
                    "description": "멱등성 키로 위젯을 생성합니다.",
                    "parameters": [
                        {
                            "name": "Idempotency-Key",
                            "in": "header",
                            "required": True,
                            "description": "24시간 요청 식별자",
                            "example": "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                            "schema": {
                                "type": "string",
                                "format": "uuid",
                                "pattern": "^[0-9a-f-]{36}$",
                            },
                        }
                    ],
                    "requestBody": {
                        "required": True,
                        "content": {
                            "application/json": {
                                "schema": {"$ref": "#/components/schemas/WidgetRequest"},
                                "example": {"name": "제주 위젯", "priority": 1},
                            }
                        },
                    },
                    "responses": {
                        "201": {
                            "description": "생성 완료",
                            "headers": {
                                "X-Trace-Id": {"$ref": "#/components/headers/TraceId"},
                            },
                            "content": {
                                "application/json": {
                                    "schema": {"$ref": "#/components/schemas/WidgetResponse"},
                                    "example": {
                                        "id": "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                                        "name": "제주 위젯",
                                    },
                                }
                            },
                        },
                        "400": {
                            "description": "요청 오류",
                            "headers": {"X-Trace-Id": {"$ref": "#/components/headers/TraceId"}},
                            "content": {
                                "application/problem+json": {
                                    "schema": {"$ref": "#/components/schemas/Problem"},
                                    "example": problem_example,
                                }
                            },
                        },
                    },
                }
            }
        },
        "components": {
            "securitySchemes": {
                "bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}
            },
            "headers": {
                "Location": {
                    "description": "생성한 리소스의 상대 URI",
                    "required": True,
                    "schema": {"type": "string", "format": "uri"},
                    "example": "/api/v1/me/018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                },
                "TraceId": {
                    "description": "요청 추적 식별자",
                    "required": True,
                    "schema": {"type": "string", "pattern": "^[0-9a-f]{32}$"},
                    "example": "0123456789abcdef0123456789abcdef",
                },
            },
            "schemas": {
                "WidgetRequest": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": ["name", "priority"],
                    "properties": {
                        "name": {"type": "string", "minLength": 1, "maxLength": 50},
                        "priority": {"type": "integer", "minimum": 1, "maximum": 5},
                    },
                },
                "WidgetResponse": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": ["id", "name"],
                    "properties": {
                        "id": {"type": "string", "format": "uuid"},
                        "name": {"type": "string"},
                    },
                },
                "Problem": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": [
                        "type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"
                    ],
                    "properties": {
                        "type": {"type": "string", "format": "uri"},
                        "title": {"type": "string"},
                        "status": {"type": "integer", "minimum": 400, "maximum": 599},
                        "detail": {"type": "string"},
                        "instance": {"type": "string", "format": "uri"},
                        "code": {"type": "string", "pattern": "^[A-Z][A-Z0-9_]*$"},
                        "traceId": {"type": "string", "pattern": "^[0-9a-f]{32}$"},
                        "fieldErrors": {"type": "array", "items": {"type": "object"}},
                    },
                },
            },
        },
    }
    current_operations = {
        ("get", "/api/v1/auth/social/providers"): "authSocialProvidersList",
        ("get", "/api/v1/auth/social/naver/userinfo"): "authNaverUserInfoRead",
        ("get", "/api/v1/me"): "profileRead",
        ("patch", "/api/v1/me"): "profileUpdate",
        ("get", "/api/v1/legal-documents"): "legalDocumentsList",
        ("put", "/api/v1/me/consents"): "legalConsentsUpdate",
        ("get", "/api/v1/places"): "placesList",
        ("get", "/api/v1/places/{placeId}"): "placesRead",
        ("get", "/api/v1/weather/forecast"): "weatherForecastRead",
    }
    template = document["paths"]["/api/v1/me"]["patch"]
    for (method, path), operation_id in current_operations.items():
        operation = copy.deepcopy(template)
        operation["operationId"] = operation_id
        if path.startswith("/api/v1/auth/social/"):
            operation["security"] = []
        elif path in {
            "/api/v1/legal-documents",
            "/api/v1/places",
            "/api/v1/places/{placeId}",
            "/api/v1/weather/forecast",
        }:
            operation["security"] = [{}, {"bearerAuth": []}]
        else:
            operation["security"] = [{"bearerAuth": []}]
            forbidden = copy.deepcopy(operation["responses"]["400"])
            forbidden["description"] = "접근 거부"
            forbidden["content"]["application/problem+json"]["example"].update(
                status=403,
                code="AUTH_ACCESS_DENIED",
                type="https://api.timing-jeju.example/problems/auth-access-denied",
            )
            operation["responses"]["403"] = forbidden
        document["paths"].setdefault(path, {})[method] = operation
    return document


class OpenApiFrontendReadinessTest(unittest.TestCase):
    def run_validator(self, document=None, path=None, *arguments):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(path) if path else Path(directory) / "openapi.json"
            if document is not None:
                artifact.write_text(json.dumps(document), encoding="utf-8")
            mode_arguments = arguments or ("--mode", "9")
            return subprocess.run(
                ["python3", str(VALIDATOR), str(artifact), *mode_arguments],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )

    def assert_rejected(self, mutate, message):
        document = valid_document()
        mutate(document)
        result = self.run_validator(document)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(message, result.stderr)

    def test_clean_checkout에서_artifact가_없으면_skip하지_않고_실패한다(self):
        result = self.run_validator(path=ROOT / "missing-openapi-artifact.json")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("OpenAPI artifact가 없습니다", result.stderr)

    def test_frontend_ready_openapi는_통과한다(self):
        validator = Validator(valid_document(), 9, ROOT)
        self.assertEqual([], validator.validate(include_authority=False))

    def test_openapi_31_ref_sibling_nullable을_보존한다(self):
        document = valid_document()
        document["components"]["schemas"]["NullableName"] = {"type": "string"}
        document["components"]["schemas"]["WidgetResponse"]["properties"]["name"] = {
            "$ref": "#/components/schemas/NullableName",
            "type": ["string", "null"],
        }
        document["paths"]["/api/v1/me"]["patch"]["responses"]["201"]["content"][
            "application/json"
        ]["example"]["name"] = None
        validator = Validator(document, 9, ROOT)
        self.assertEqual([], validator.validate(include_authority=False))

    def test_operation_request_response_header_media_example_mutation을_거부한다(self):
        mutations = [
            (lambda d: d["paths"]["/api/v1/me"]["patch"].pop("operationId"), "operationId"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"].update(operationId="create_1"), "operationId"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"].update(operationId="getWidgets"), "operationId"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"].update(tags=["widget-controller"]), "domain tag"),
            (lambda d: d["paths"].update({"/api/v1/gadgets": copy.deepcopy(d["paths"]["/api/v1/me"])}), "중복"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"].pop("summary"), "summary"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["requestBody"]["content"]["application/json"].pop("example"), "request example"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["requestBody"].update(required=False), "requestBody"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["responses"]["201"]["content"].__setitem__("*/*", d["paths"]["/api/v1/me"]["patch"]["responses"]["201"]["content"].pop("application/json")), "application/json"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["responses"]["201"]["content"]["application/json"].pop("example"), "success example"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["responses"]["400"]["content"]["application/problem+json"].pop("example"), "problem example"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["responses"]["400"]["content"]["application/problem+json"]["example"].update(status=409), "response 400"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["responses"]["400"]["content"]["application/problem+json"]["example"].update(code="STALE_CODE"), "type과 code"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["parameters"][0].update(required=False), "Idempotency-Key"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["parameters"][0].pop("description"), "parameter description"),
            (lambda d: d["components"]["headers"]["Location"]["schema"].pop("format"), "Location"),
            (lambda d: d["components"]["securitySchemes"].pop("bearerAuth"), "bearer"),
            (lambda d: d.update(security=[]), "전역 bearer security"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["responses"]["201"]["headers"].pop("X-Trace-Id"), "X-Trace-Id"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["responses"]["201"]["headers"].update({"X-Internal": {"schema": {"type": "string"}}}), "response header projection"),
        ]
        for mutate, message in mutations:
            with self.subTest(message=message):
                self.assert_rejected(mutate, message)

    def test_권위_source의_operation_inventory_누락을_거부한다(self):
        self.assert_rejected(
            lambda d: d["paths"].pop("/api/v1/weather/forecast"),
            "권위 source의 공개 operation이 없습니다",
        )

    def test_operation_security와_403_pipeline_drift를_거부한다(self):
        mutations = [
            (
                lambda d: d["paths"]["/api/v1/legal-documents"]["get"].update(
                    security=[{"bearerAuth": []}]
                ),
                "optional security",
            ),
            (
                lambda d: d["paths"]["/api/v1/legal-documents"]["get"][
                    "responses"
                ].update(
                    {
                        "403": copy.deepcopy(
                            d["paths"]["/api/v1/me"]["get"]["responses"]["403"]
                        )
                    }
                ),
                "403 response가 없어야",
            ),
            (
                lambda d: d["paths"]["/api/v1/me"]["get"]["responses"].pop("403"),
                "403 response가 필요",
            ),
        ]
        for mutate, message in mutations:
            with self.subTest(message=message):
                self.assert_rejected(mutate, message)

    def test_16_operation완료_mode는_future_group_전체_삭제도_거부한다(self):
        result = self.run_validator(valid_document(), None, "--mode", "16")
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("16-operation", result.stderr)

    def test_16_operation완료_mode는_두_clean_source가_HEAD_조상인지_fail_closed로_검사한다(self):
        validator = Validator(valid_document(), 16, ROOT)
        with mock.patch(
            "scripts.validate_openapi_frontend_readiness.subprocess.run",
            side_effect=[
                subprocess.CompletedProcess([], 0),
                subprocess.CompletedProcess([], 1),
            ],
        ):
            validator.validate_source_provenance()

        self.assertEqual(2, len(validator.source_provenance))
        self.assertEqual(
            "bd83872b1fd91d5e5c1980422634198734c92cf1",
            validator.source_provenance["saved-places"],
        )
        self.assertEqual(
            "9a4c4b2f78d61d8f37e8f27646f888eddd28a2de",
            validator.source_provenance["trips"],
        )
        self.assertTrue(
            any(
                "trips" in error and "source provenance" in error
                for error in validator.errors
            )
        )

    def test_schema와_example의_양방향_drift를_거부한다(self):
        mutations = [
            (lambda d: d["components"]["schemas"]["WidgetRequest"]["properties"].pop("priority"), "additional property"),
            (lambda d: d["components"]["schemas"]["WidgetRequest"].update(additionalProperties=True), "closed object"),
            (lambda d: d["components"]["schemas"]["WidgetRequest"]["properties"].update(description={"type": "string"}), "schema property"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["requestBody"]["content"]["application/json"]["example"].pop("priority"), "required property"),
            (lambda d: d["components"]["schemas"]["WidgetRequest"]["properties"]["priority"].update(maximum=0), "maximum"),
            (lambda d: d["paths"]["/api/v1/me"]["patch"]["requestBody"]["content"]["application/json"]["example"].update(priority="high"), "type"),
        ]
        for mutate, message in mutations:
            with self.subTest(message=message):
                self.assert_rejected(mutate, message)

    def test_canonical_projection은_schema_constraint_완화와_변조를_거부한다(self):
        canonical = {
            "Canonical": {
                "type": "object",
                "nullable": False,
                "additionalProperties": False,
                "required": ["name", "count"],
                "minProperties": 2,
                "properties": {
                    "name": {
                        "type": "string",
                        "nullable": False,
                        "minLength": 1,
                        "maxLength": 20,
                        "pattern": "^[a-z]+$",
                        "format": "slug",
                        "enum": ["jeju"],
                        "default": "jeju",
                    },
                    "count": {
                        "type": "integer",
                        "nullable": True,
                        "minimum": 1,
                        "maximum": 10,
                    },
                    "items": {
                        "type": "array",
                        "items": {
                            "type": "array",
                            "items": {"type": "string", "minLength": 1},
                        },
                    },
                },
            }
        }
        actual = copy.deepcopy(canonical["Canonical"])

        mutations = [
            lambda schema: schema.pop("minProperties"),
            lambda schema: schema["required"].remove("count"),
            lambda schema: schema["properties"]["name"].pop("minLength"),
            lambda schema: schema["properties"]["name"].update(maxLength=200),
            lambda schema: schema["properties"]["name"].pop("pattern"),
            lambda schema: schema["properties"]["name"].pop("format"),
            lambda schema: schema["properties"]["name"].update(enum=["other"]),
            lambda schema: schema["properties"]["name"].pop("default"),
            lambda schema: schema["properties"]["count"].pop("minimum"),
            lambda schema: schema["properties"]["count"].update(maximum=100),
            lambda schema: schema["properties"]["count"].update(nullable=False),
            lambda schema: schema.update(additionalProperties=True),
            lambda schema: schema["properties"]["items"].pop("items"),
            lambda schema: schema["properties"]["name"].update(items={"type": "string"}),
            lambda schema: schema["properties"]["items"]["items"]["items"].pop("minLength"),
        ]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                changed = copy.deepcopy(actual)
                mutate(changed)
                validator = Validator(valid_document(), 9, ROOT)
                validator.compare_schema(
                    {"$ref": "Canonical"}, changed, canonical, "canonical mutation"
                )
                self.assertTrue(validator.errors)

    def test_canonical_request_header는_누락과_추가를_양방향_거부한다(self):
        catalog = {"schemas": {"headers": "CreateHeaders"}}
        schemas = {
            "CreateHeaders": {
                "type": "object",
                "additionalProperties": False,
                "required": ["Idempotency-Key"],
                "properties": {
                    "Idempotency-Key": {"type": "string", "format": "uuid"}
                },
            }
        }
        expected_parameter = {
            "in": "header",
            "name": "Idempotency-Key",
            "required": True,
            "schema": {"type": "string", "format": "uuid"},
        }
        baseline = Validator(valid_document(), 16, ROOT)
        baseline.validate_contract_parameters(
            {"parameters": [expected_parameter]}, catalog, schemas, "POST /canonical"
        )
        self.assertFalse(baseline.errors, baseline.errors)
        for parameters in (
            [],
            [
                expected_parameter,
                {
                    "in": "header",
                    "name": "X-Internal",
                    "required": True,
                    "schema": {"type": "string"},
                },
            ],
        ):
            with self.subTest(parameters=parameters):
                validator = Validator(valid_document(), 16, ROOT)
                validator.validate_contract_parameters(
                    {"parameters": parameters}, catalog, schemas, "POST /canonical"
                )
                self.assertTrue(
                    any("request header projection" in error for error in validator.errors)
                )

    def test_canonical_projection은_status와_problem_code_삭제_추가_변조를_거부한다(self):
        key = ("GET", "/api/v1/me/saved-places")
        catalog = {
            "schemas": {"path": "none", "query": "none", "headers": "CommonHeaders", "body": "none"},
            "responses": {"success": [200], "errors": [400]},
        }
        endpoint = {
            "successSchema": "none",
            "successStatuses": [200],
            "problems": [{"status": 400, "code": "INVALID_QUERY_PARAMETER"}],
        }

        def document():
            return {
                "paths": {
                    key[1]: {
                        "get": {
                            "responses": {
                                "200": {"description": "ok"},
                                "400": {
                                    "content": {
                                        "application/problem+json": {
                                            "example": {
                                                "code": "INVALID_QUERY_PARAMETER",
                                                "type": "https://api.timing-jeju.com/problems/invalid-query-parameter",
                                            }
                                        }
                                    }
                                },
                                "403": {
                                    "content": {
                                        "application/problem+json": {
                                            "example": {
                                                "code": "AUTH_ACCESS_DENIED",
                                                "type": "https://api.timing-jeju.example/problems/auth-access-denied",
                                            }
                                        }
                                    }
                                },
                                "500": {
                                    "content": {
                                        "application/problem+json": {
                                            "example": {
                                                "code": "INTERNAL_SERVER_ERROR",
                                                "type": "https://api.timing-jeju.example/problems/internal-server-error",
                                            }
                                        }
                                    }
                                },
                            }
                        }
                    }
                }
            }

        mutations = [
            lambda d: d["paths"][key[1]]["get"]["responses"].pop("400"),
            lambda d: d["paths"][key[1]]["get"]["responses"].update({"422": {}}),
            lambda d: d["paths"][key[1]]["get"]["responses"]["400"]["content"][
                "application/problem+json"
            ]["example"].update(code="STALE_CODE"),
            lambda d: d["paths"][key[1]]["get"]["responses"]["400"]["content"][
                "application/problem+json"
            ]["example"].update(
                type="https://api.timing-jeju.example/problems/invalid-query-parameter"
            ),
        ]
        baseline = Validator(document(), 16, ROOT)
        manifest = baseline.read_authority_json(
            "scripts/openapi_frontend_runtime_manifest.json"
        )
        baseline.runtime_manifest = manifest["operations"]
        baseline.runtime_problem_definitions = manifest["runtimeProblemDefinitions"]
        domain_pairs = {
            (
                "INVALID_QUERY_PARAMETER",
                "https://api.timing-jeju.com/problems/invalid-query-parameter",
            )
        }
        baseline.validate_contract_endpoint(key, catalog, endpoint, {}, domain_pairs)
        self.assertFalse(baseline.errors, baseline.errors)
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                candidate = document()
                mutate(candidate)
                validator = Validator(candidate, 16, ROOT)
                validator.runtime_manifest = manifest["operations"]
                validator.runtime_problem_definitions = manifest[
                    "runtimeProblemDefinitions"
                ]
                validator.validate_contract_endpoint(
                    key, catalog, endpoint, {}, domain_pairs
                )
                self.assertTrue(validator.errors)

        drifted = copy.deepcopy(manifest["operations"])
        drifted["GET /api/v1/me/saved-places"]["problems"]["400"] = [
            "STALE_CODE",
            "https://api.timing-jeju.com/problems/stale-code",
        ]
        validator = Validator(document(), 16, ROOT)
        validator.runtime_manifest = drifted
        validator.runtime_problem_definitions = manifest["runtimeProblemDefinitions"]
        validator.validate_contract_endpoint(key, catalog, endpoint, {}, domain_pairs)
        self.assertTrue(
            any("domain endpoint matrix" in error for error in validator.errors)
        )

    def test_mode9도_current_domain_authority_7개를_전부_projection한다(self):
        validator = Validator(valid_document(), 9, ROOT)
        with mock.patch.object(validator, "validate_contract_endpoint") as projection:
            validator.validate_contract_authority()
        projected = {call.args[0] for call in projection.call_args_list}
        self.assertEqual(
            {
                ("GET", "/api/v1/me"),
                ("PATCH", "/api/v1/me"),
                ("GET", "/api/v1/legal-documents"),
                ("PUT", "/api/v1/me/consents"),
                ("GET", "/api/v1/places"),
                ("GET", "/api/v1/places/{placeId}"),
                ("GET", "/api/v1/weather/forecast"),
            },
            projected,
        )

    def test_7개_operation의_대표_problem_code는_endpoint_status별_exact_mapping이다(self):
        expected = {
            ("GET", "/api/v1/me/saved-places", 400): "INVALID_QUERY_PARAMETER",
            ("POST", "/api/v1/me/saved-places", 409): "IDEMPOTENCY_PAYLOAD_CONFLICT",
            ("PATCH", "/api/v1/me/saved-places/{placeId}", 409): "SAVED_PLACE_VERSION_CONFLICT",
            ("DELETE", "/api/v1/me/saved-places/{placeId}", 404): "SAVED_PLACE_NOT_FOUND",
            ("GET", "/api/v1/trips", 503): "TRIP_DATA_UNAVAILABLE",
            ("POST", "/api/v1/trips", 409): "IDEMPOTENCY_KEY_REUSED",
            ("GET", "/api/v1/trips/{tripId}", 404): "TRIP_NOT_FOUND",
        }
        validator = Validator(valid_document(), 16, ROOT)
        for (method, path, status), code in expected.items():
            with self.subTest(method=method, path=path, status=status):
                self.assertEqual(
                    code,
                    validator.expected_problem_code((method, path), status),
                )
        self.assertEqual(
            "https://api.timing-jeju.example/problems/idempotency-key-reused",
            validator.expected_problem_type("IDEMPOTENCY_KEY_REUSED"),
        )

    def test_secret_like_example과_internal_endpoint를_거부한다(self):
        self.assert_rejected(
            lambda d: d["components"]["headers"]["TraceId"].update(example="sk_live_51ABCDEF0123456789"),
            "secret-like",
        )
        self.assert_rejected(
            lambda d: d["paths"].update({"/actuator/health": copy.deepcopy(d["paths"]["/api/v1/me"])}),
            "internal endpoint",
        )
        self.assert_rejected(
            lambda d: d["paths"].update({"/api/v1/demo/storage": copy.deepcopy(d["paths"]["/api/v1/me"])}),
            "public inventory allowlist",
        )
        self.assert_rejected(
            lambda d: d["paths"].update({"/api/v1/internal/mcp": copy.deepcopy(d["paths"]["/api/v1/me"])}),
            "public inventory allowlist",
        )


if __name__ == "__main__":
    unittest.main()
