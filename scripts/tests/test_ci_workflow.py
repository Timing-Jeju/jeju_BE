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

    def test_pr_policy_and_service_jobs_are_separated(self):
        self.assertIn("scripts/github/validate-pr-metadata.py", self.workflow)
        for job in (
            "changes:",
            "common-check:",
            "spring-check:",
            "fastapi-check:",
            "contract-check:",
            "quality-gate:",
        ):
            with self.subTest(job=job):
                self.assertIn(job, self.workflow)

        self.assertIn("scripts/ci/detect_changes.py", self.workflow)
        self.assertIn("./scripts/quality-gate.sh --ci --scope common", self.workflow)
        self.assertIn("./scripts/quality-gate.sh --ci --scope spring", self.workflow)
        self.assertIn("./scripts/quality-gate.sh --ci --scope fastapi", self.workflow)

    def test_fastapi_job_uses_pinned_python_and_uv(self):
        self.assertIn("python-version-file: services/fastapi-mcp/.python-version", self.workflow)
        self.assertIn("uses: astral-sh/setup-uv@08807647e7069bb48b6ef5acd8ec9567f424441b", self.workflow)
        self.assertIn("version: '0.11.32'", self.workflow)

    def test_test_reports_are_preserved(self):
        self.assertIn("services/spring-api/build/reports/tests/", self.workflow)
        self.assertIn("services/spring-api/build/reports/jacoco/", self.workflow)


if __name__ == "__main__":
    unittest.main()
