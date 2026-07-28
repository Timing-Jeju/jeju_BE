from __future__ import annotations

import unittest

from scripts.ci.detect_changes import classify_paths


class DetectChangesTest(unittest.TestCase):
    def test_spring_change_runs_only_spring_service_check(self):
        result = classify_paths(["services/spring-api/src/main/java/App.java"])

        self.assertEqual(
            {"spring": True, "fastapi": False, "contract": False}, result
        )

    def test_fastapi_change_runs_only_fastapi_service_check(self):
        result = classify_paths(["services/fastapi-mcp/pyproject.toml"])

        self.assertEqual(
            {"spring": False, "fastapi": True, "contract": False}, result
        )

    def test_contract_change_runs_both_services_and_contract_check(self):
        result = classify_paths(
            ["docs/designs/timing-jeju-spring-fastapi-integration-contract.md"]
        )

        self.assertEqual(
            {"spring": True, "fastapi": True, "contract": True}, result
        )

    def test_ci_or_root_gate_change_runs_both_services(self):
        for path in (".github/workflows/ci.yml", "scripts/quality-gate.sh"):
            with self.subTest(path=path):
                result = classify_paths([path])
                self.assertEqual(
                    {"spring": True, "fastapi": True, "contract": False}, result
                )

    def test_unrelated_document_change_skips_service_checks(self):
        result = classify_paths(["docs/GIT_WORKFLOW.md"])

        self.assertEqual(
            {"spring": False, "fastapi": False, "contract": False}, result
        )


if __name__ == "__main__":
    unittest.main()
