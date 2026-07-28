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
