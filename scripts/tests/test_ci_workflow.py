from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"


class CiWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = CI_WORKFLOW.read_text(encoding="utf-8")

    def test_pull_request_and_protected_branch_push_run_ci(self):
        self.assertIn("pull_request:", self.workflow)
        self.assertIn("push:", self.workflow)
        self.assertGreaterEqual(self.workflow.count("branches: [develop, main]"), 2)

    def test_stale_runs_are_cancelled_per_branch(self):
        self.assertIn("concurrency:", self.workflow)
        self.assertIn("github.workflow", self.workflow)
        self.assertIn("github.head_ref || github.ref", self.workflow)
        self.assertIn("cancel-in-progress: true", self.workflow)

    def test_ci_uses_minimum_permissions_and_java_21(self):
        self.assertIn("permissions:\n  contents: read", self.workflow)
        self.assertIn("uses: actions/setup-java@v4", self.workflow)
        self.assertIn("java-version: '21'", self.workflow)
        self.assertIn("uses: gradle/actions/wrapper-validation@v4", self.workflow)
        self.assertIn("uses: gradle/actions/setup-gradle@v4", self.workflow)

    def test_pr_policy_and_backend_jobs_are_separated(self):
        self.assertIn("scripts/github/validate-pr-metadata.py", self.workflow)
        for job in (
            "changes:",
            "common-check:",
            "spring-check:",
            "contract-check:",
            "quality-gate:",
        ):
            with self.subTest(job=job):
                self.assertIn(job, self.workflow)

        self.assertIn("scripts/ci/detect_changes.py", self.workflow)
        self.assertIn("./scripts/quality-gate.sh --ci --scope common", self.workflow)
        self.assertIn("./scripts/quality-gate.sh --ci --scope spring", self.workflow)
        self.assertNotIn("fastapi-check:", self.workflow)
        self.assertNotIn("--scope fastapi", self.workflow)

    def test_backend_ci_does_not_install_python_or_uv(self):
        self.assertNotIn("actions/setup-python", self.workflow)
        self.assertNotIn("astral-sh/setup-uv", self.workflow)
        self.assertNotIn("services/fastapi-mcp", self.workflow)

    def test_spring_check_fetches_full_history_for_openapi_provenance(self):
        self.assert_spring_check_fetches_full_history(self.workflow)
        spring_job = self.workflow.split("\n  spring-check:", 1)[1].split(
            "\n  contract-check:", 1
        )[0]
        mutations = {
            "removed": spring_job.replace("          fetch-depth: 0\n", "", 1),
            "shallow": spring_job.replace(
                "          fetch-depth: 0", "          fetch-depth: 1", 1
            ),
            "comment_only": spring_job.replace(
                "          fetch-depth: 0", "          # fetch-depth: 0", 1
            ),
        }
        for scenario, mutated_spring_job in mutations.items():
            mutation = self.workflow.replace(spring_job, mutated_spring_job, 1)
            with self.subTest(scenario=scenario), self.assertRaises(AssertionError):
                self.assert_spring_check_fetches_full_history(mutation)

    def assert_spring_check_fetches_full_history(self, workflow):
        spring_job = workflow.split("\n  spring-check:", 1)[1].split(
            "\n  contract-check:", 1
        )[0]
        checkout_step = spring_job.split("      - name: 저장소 Checkout", 1)[1].split(
            "\n      - name:", 1
        )[0]
        active_checkout_lines = {
            line.strip()
            for line in checkout_step.splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
        self.assertIn("uses: actions/checkout@v4", active_checkout_lines)
        self.assertIn("with:", active_checkout_lines)
        self.assertIn("fetch-depth: 0", active_checkout_lines)

    def test_test_reports_are_preserved(self):
        self.assertIn("services/spring-api/build/reports/tests/", self.workflow)
        self.assertIn("services/spring-api/build/reports/jacoco/", self.workflow)
        self.assertIn("services/spring-api/build/openapi/openapi.json", self.workflow)


if __name__ == "__main__":
    unittest.main()
