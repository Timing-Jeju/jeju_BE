from __future__ import annotations

import tomllib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPRING_API = ROOT / "services" / "spring-api"
FASTAPI_MCP = ROOT / "services" / "fastapi-mcp"


class MonorepoLayoutTest(unittest.TestCase):
    def test_spring_server_is_isolated_under_services(self):
        required_paths = (
            "build.gradle",
            "settings.gradle",
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "src/main/java/com/timingjeju/api/TimingJejuApiApplication.java",
            "src/test/java/com/timingjeju/api/architecture/ArchitectureTest.java",
            "Dockerfile",
            "AGENTS.md",
        )

        for relative_path in required_paths:
            with self.subTest(path=relative_path):
                self.assertTrue((SPRING_API / relative_path).is_file())

    def test_spring_server_files_do_not_remain_at_repository_root(self):
        backend_only_paths = (
            "build.gradle",
            "settings.gradle",
            "gradlew",
            "gradlew.bat",
            "gradle",
            "src",
            "Dockerfile",
        )

        for relative_path in backend_only_paths:
            with self.subTest(path=relative_path):
                self.assertFalse((ROOT / relative_path).exists())

    def test_common_assets_remain_at_repository_root(self):
        common_paths = (
            "AGENTS.md",
            "README.md",
            "docs",
            "db",
            "fixtures",
            ".github",
            ".codex",
            ".githooks",
            "compose.yml",
            "compose.test.yml",
            "scripts/quality-gate.sh",
        )

        for relative_path in common_paths:
            with self.subTest(path=relative_path):
                self.assertTrue((ROOT / relative_path).exists())

    def test_fastapi_service_has_only_structure_neutral_baseline(self):
        required_paths = (
            "README.md",
            "AGENTS.md",
            ".python-version",
            "pyproject.toml",
            "uv.lock",
            "scripts/quality-gate.sh",
        )

        for relative_path in required_paths:
            with self.subTest(path=relative_path):
                self.assertTrue((FASTAPI_MCP / relative_path).is_file())

        self.assertFalse((FASTAPI_MCP / "src").exists())
        self.assertFalse((FASTAPI_MCP / "app").exists())

    def test_fastapi_tooling_does_not_force_a_package_layout(self):
        with (FASTAPI_MCP / "pyproject.toml").open("rb") as file:
            pyproject = tomllib.load(file)

        self.assertEqual(">=3.12,<3.13", pyproject["project"]["requires-python"])
        self.assertFalse(pyproject["tool"]["uv"]["package"])

        dependencies = pyproject["project"]["dependencies"]
        development_dependencies = pyproject["dependency-groups"]["dev"]
        for dependency in ("fastapi", "mcp"):
            self.assertTrue(any(item.startswith(dependency) for item in dependencies))
        for dependency in ("ruff", "mypy", "pytest"):
            self.assertTrue(
                any(item.startswith(dependency) for item in development_dependencies)
            )

    def test_root_automation_targets_the_spring_service_directory(self):
        quality_gate = (ROOT / "scripts" / "quality-gate.sh").read_text(
            encoding="utf-8"
        )
        compose = (ROOT / "compose.yml").read_text(encoding="utf-8")
        test_compose = (ROOT / "compose.test.yml").read_text(encoding="utf-8")

        self.assertIn("services/spring-api", quality_gate)
        self.assertIn("services/fastapi-mcp", quality_gate)
        self.assertIn("./services/spring-api", compose)
        self.assertIn("./services/spring-api", test_compose)


if __name__ == "__main__":
    unittest.main()
