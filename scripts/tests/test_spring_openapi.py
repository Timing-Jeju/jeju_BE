from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPRING_API = ROOT / "services" / "spring-api"


class SpringOpenApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build_gradle = (SPRING_API / "build.gradle").read_text(encoding="utf-8")
        cls.application_yml = (
            SPRING_API / "src" / "main" / "resources" / "application.yml"
        ).read_text(encoding="utf-8")

    def test_springdoc_swagger_ui_dependency_is_fixed(self):
        self.assertIn(
            "org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3",
            self.build_gradle,
        )
        quality_gate = (ROOT / "scripts" / "quality-gate.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("run_spring_gradle openApiDocs", quality_gate)

    def test_quality_gate_validates_generated_artifact_after_generation(self):
        quality_gate = (ROOT / "scripts" / "quality-gate.sh").read_text(
            encoding="utf-8"
        )
        generation = quality_gate.index("run_spring_gradle openApiDocs")
        validation = quality_gate.index(
            "python3 scripts/validate_openapi_frontend_readiness.py"
        )
        self.assertLess(generation, validation)
        windows_gate = (ROOT / "scripts" / "quality-gate.ps1").read_text(
            encoding="utf-8"
        )
        windows_generation = windows_gate.index(
            "./gradlew.bat --no-daemon openApiDocs"
        )
        windows_validation = windows_gate.index(
            "validate_openapi_frontend_readiness.py"
        )
        self.assertLess(windows_generation, windows_validation)
        self.assertIn(
            'Invoke-Native "frontend OpenAPI 준비도 검사"',
            windows_gate,
        )
        self.assertIn("--contracts-root ../..", windows_gate)
        self.assert_windows_gate_fail_closed(windows_gate)
        unwrapped = windows_gate.replace(
            'Invoke-Native "Spring OpenAPI 문서 생성" { ./gradlew.bat --no-daemon openApiDocs }',
            './gradlew.bat --no-daemon openApiDocs',
        )
        with self.assertRaises(AssertionError):
            self.assert_windows_gate_fail_closed(unwrapped)

    def assert_windows_gate_fail_closed(self, windows_gate):
        gradle_lines = [line.strip() for line in windows_gate.splitlines() if "./gradlew.bat" in line]
        self.assertTrue(gradle_lines)
        self.assertTrue(all(line.startswith("Invoke-Native") for line in gradle_lines))
        stale_delete = windows_gate.index(
            'Remove-Item -LiteralPath "build/openapi/openapi.json" -Force -ErrorAction Stop'
        )
        generation = windows_gate.index("./gradlew.bat --no-daemon openApiDocs")
        validation = windows_gate.index("validate_openapi_frontend_readiness.py")
        self.assertLess(stale_delete, generation)
        self.assertLess(generation, validation)

    def test_windows_openapi_stale_artifact_delete_is_fail_closed(self):
        gate = (ROOT / "scripts" / "quality-gate.ps1").read_text(encoding="utf-8")
        self.assert_windows_stale_delete_contract(gate)
        mutations = {
            "absent": gate.replace(
                'if (Test-Path -LiteralPath "build/openapi/openapi.json") {',
                'if ($true) {',
                1,
            ),
            "locked": gate.replace("-ErrorAction Stop", "-ErrorAction SilentlyContinue", 1),
            "normal": gate.replace(
                'if (Test-Path -LiteralPath "build/openapi/openapi.json") {\n      throw "stale OpenAPI artifact를 삭제하지 못했습니다."\n    }',
                "",
            ),
        }
        for scenario, mutation in mutations.items():
            with self.subTest(scenario=scenario), self.assertRaises(AssertionError):
                self.assert_windows_stale_delete_contract(mutation)

    def assert_windows_stale_delete_contract(self, gate):
        self.assertIn('if (Test-Path -LiteralPath "build/openapi/openapi.json")', gate)
        self.assertIn(
            'Remove-Item -LiteralPath "build/openapi/openapi.json" -Force -ErrorAction Stop',
            gate,
        )
        self.assertIn("stale OpenAPI artifact", gate)
        self.assertGreaterEqual(
            gate.count('Test-Path -LiteralPath "build/openapi/openapi.json"'), 2
        )

    def test_frontend_customizer는_inferred_schema_drift를_false로_덮지_않는다(self):
        customizer = (
            SPRING_API
            / "src/main/java/com/timingjeju/api/global/config/FrontendOpenApiCustomizer.java"
        ).read_text(encoding="utf-8")
        self.assertNotIn("setAdditionalProperties(false)", customizer)

    def test_canonical_contract_resource를_런타임_swagger에_fail_closed_projection한다(self):
        customizer = (
            SPRING_API
            / "src/main/java/com/timingjeju/api/global/config/FrontendOpenApiCustomizer.java"
        ).read_text(encoding="utf-8")
        self.assertIn("srcDir file('../../docs/contracts')", self.build_gradle)
        self.assertIn("projectCanonicalContracts(openApi)", customizer)
        for resource in (
            '"/rest/catalog.json"',
            '"profile-legal"',
            '"places"',
            '"weather-forecast"',
            '"saved-places"',
            '"trips"',
        ):
            self.assertIn(resource, customizer)
        self.assertIn("canonical parameter가 없습니다", customizer)
        self.assertIn("canonical contract resource가 없습니다", customizer)
        self.assertIn("schema.setTypes(nullable ? Set.of(type, \"null\")", customizer)

    def test_closed_response_shape은_각_authority_DTO에_명시한다(self):
        sources = (
            "global/error/ApiProblemDetails.java",
            "global/error/FieldErrorDetail.java",
            "domain/auth/dto/response/SocialLoginProviderResponse.java",
            "domain/auth/dto/response/SocialLoginProvidersResponse.java",
            "domain/auth/dto/response/NaverUserInfoResponse.java",
        )
        java_root = SPRING_API / "src/main/java/com/timingjeju/api"
        for relative_path in sources:
            with self.subTest(source=relative_path):
                content = (java_root / relative_path).read_text(encoding="utf-8")
                self.assertIn(
                    "additionalProperties = Schema.AdditionalPropertiesValue.FALSE",
                    content,
                )

    def test_saved_places_non_contributor_problem도_runtime_definition으로_해결한다(self):
        customizer = (
            SPRING_API
            / "src/main/java/com/timingjeju/api/global/config/FrontendOpenApiCustomizer.java"
        ).read_text(encoding="utf-8")
        parameter_section = customizer.split("PARAMETER_EXAMPLES", 1)[1].split(
            "NON_CONTRIBUTOR_PROBLEM_DEFINITIONS", 1
        )[0]
        definition_section = customizer.split(
            "NON_CONTRIBUTOR_PROBLEM_DEFINITIONS", 1
        )[1].split("DOCUMENTS", 1)[0]
        for code in (
            "INVALID_REQUEST",
            "INVALID_QUERY_PARAMETER",
            "PLACE_NOT_FOUND",
            "IDEMPOTENCY_PAYLOAD_CONFLICT",
            "SAVED_PLACE_VERSION_CONFLICT",
            "SAVED_PLACE_NOT_FOUND",
            "SAVED_PLACE_CONSTRAINT_VIOLATION",
        ):
            self.assertNotIn(code, parameter_section)
            self.assertRegex(definition_section, rf'nonContributorProblem\(\s*"{code}"')
        self.assertIn("boolean savedPlaceOperation", customizer)
        self.assertIn("savedPlaceOperation ? NON_CONTRIBUTOR_PROBLEM_DEFINITIONS.get(code)", customizer)
        self.assert_saved_problem_resolution_is_fail_closed(customizer)

    def assert_saved_problem_resolution_is_fail_closed(self, customizer):
        method = customizer.split(
            "private Map<String, Object> problemExample", 1
        )[1].split("private static ProblemDefinition nonContributorProblem", 1)[0]
        override = method.index("NON_CONTRIBUTOR_PROBLEM_DEFINITIONS.get(code)")
        fallback = method.index("definition = problemCodeRegistry.find(code)")
        validation = method.index(
            "definition == null || definition.status() != status || !definition.code().equals(code)"
        )
        self.assertLess(override, fallback)
        self.assertLess(fallback, validation)
        mutations = (
            method.replace("definition = problemCodeRegistry.find(code)", "definition = null"),
            method.replace("definition.status() != status || ", ""),
            method.replace("!definition.code().equals(code)", "false"),
        )
        for mutation in mutations:
            with self.assertRaises((AssertionError, ValueError)):
                override = mutation.index("NON_CONTRIBUTOR_PROBLEM_DEFINITIONS.get(code)")
                fallback = mutation.index("definition = problemCodeRegistry.find(code)")
                validation = mutation.index(
                    "definition == null || definition.status() != status || !definition.code().equals(code)"
                )
                self.assertLess(override, fallback)
                self.assertLess(fallback, validation)

    def test_saved_place_generated_artifact는_standard와_override_problem을_함께_검사한다(self):
        integration_test = (
            SPRING_API
            / "src/test/java/com/timingjeju/api/documentation/OpenApiDocumentationTest.java"
        ).read_text(encoding="utf-8")
        section = integration_test.split(
            "saved_place가_병합되면_표준_401과_non_contributor_409_example을_정확히_문서화한다",
            1,
        )[1].split("@Test", 1)[0]
        for expected in (
            'path("401")',
            'isEqualTo("AUTH_TOKEN_INVALID")',
            'isEqualTo("https://api.timing-jeju.example/problems/auth-token-invalid")',
            'path("409")',
            'isEqualTo("IDEMPOTENCY_PAYLOAD_CONFLICT")',
            'isEqualTo("https://api.timing-jeju.com/problems/idempotency-payload-conflict")',
        ):
            self.assertIn(expected, section)

    def test_saved_place_presentation은_description과_inline_header를_exact_merge한다(self):
        customizer = (
            SPRING_API
            / "src/main/java/com/timingjeju/api/global/config/FrontendOpenApiCustomizer.java"
        ).read_text(encoding="utf-8")
        self.assertIn('"POST /api/v1/me/saved-places"', customizer)
        self.assertIn('"DELETE /api/v1/me/saved-places/{placeId}"', customizer)
        self.assertIn("operation.setDescription", customizer)
        self.assertIn("mergeRequiredHeader", customizer)
        self.assertIn('new StringSchema().pattern("^[A-Za-z0-9._:-]{1,128}$")', customizer)
        self.assertIn('pattern("^\\\\\\\"[A-Za-z0-9._:-]{1,128}\\\\\\\"$")', customizer)
        self.assertIn('"saved-place-create-34"', customizer)
        self.assertIn('"\\\"saved-place.34.v1\\\""', customizer)

    def test_saved_place_delete는_request_body와_success_content가_없다(self):
        document = (ROOT / "docs" / "FRONTEND_API_SPEC.md").read_text(encoding="utf-8")
        section = document.split("### `DELETE /api/v1/me/saved-places/{placeId}`", 1)[1]
        section = section.split("### `GET /api/v1/trips`", 1)[0]
        self.assertNotIn("**성공 예시**", section)
        self.assertNotIn("```json\n{}", section)
        self.assertIn("request body 없음", section)
        self.assertIn("response content 없음", section)

    def test_only_public_api_paths_are_exposed(self):
        self.assertIn("paths-to-match: /api/v1/**", self.application_yml)
        self.assertIn("SPRINGDOC_API_DOCS_ENABLED", self.application_yml)
        self.assertIn("SPRINGDOC_SWAGGER_UI_ENABLED", self.application_yml)

    def test_global_configuration_owns_common_openapi_metadata(self):
        config = (
            SPRING_API
            / "src"
            / "main"
            / "java"
            / "com"
            / "timingjeju"
            / "api"
            / "global"
            / "config"
            / "OpenApiConfig.java"
        )

        self.assertTrue(config.is_file())
        content = config.read_text(encoding="utf-8")
        self.assertIn("new OpenAPI()", content)
        self.assertIn('title("Timing Jeju API")', content)
        self.assertNotIn("@OpenAPIDefinition", content)

    def test_korean_document_explains_annotation_minimization_policy(self):
        document = ROOT / "docs" / "API_DOCUMENTATION.md"

        self.assertTrue(document.is_file())
        content = document.read_text(encoding="utf-8")
        self.assertIn("문서 계약 인터페이스", content)
        self.assertIn("Controller 구현", content)
        self.assertIn("OpenApiCustomizer", content)


if __name__ == "__main__":
    unittest.main()
