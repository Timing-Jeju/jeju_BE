import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class SnapshotRetentionSchedulerConfigurationTest(unittest.TestCase):
    def test_application_defaults_are_opt_in_and_bounded(self):
        application = (
            ROOT
            / "services"
            / "spring-api"
            / "src"
            / "main"
            / "resources"
            / "application.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("enabled: ${SNAPSHOT_RETENTION_SCHEDULE_ENABLED:false}", application)
        self.assertIn(
            "fixed-delay: ${SNAPSHOT_RETENTION_SCHEDULE_FIXED_DELAY:PT24H}",
            application,
        )
        self.assertIn(
            "initial-delay: ${SNAPSHOT_RETENTION_SCHEDULE_INITIAL_DELAY:PT1M}",
            application,
        )
        self.assertIn(
            "max-batches: ${SNAPSHOT_RETENTION_SCHEDULE_MAX_BATCHES:10}",
            application,
        )
        self.assertIn(
            "retry-attempts: ${SNAPSHOT_RETENTION_SCHEDULE_RETRY_ATTEMPTS:3}",
            application,
        )
        self.assertIn(
            "initial-backoff: ${SNAPSHOT_RETENTION_SCHEDULE_INITIAL_BACKOFF:PT0.25S}",
            application,
        )

    def test_env_example_has_only_non_secret_bounded_schedule_controls(self):
        example = (ROOT / ".env.example").read_text(encoding="utf-8")

        self.assertIn("SNAPSHOT_RETENTION_SCHEDULE_ENABLED=false", example)
        self.assertIn("SNAPSHOT_RETENTION_SCHEDULE_MAX_BATCHES=10", example)
        self.assertIn("SNAPSHOT_RETENTION_SCHEDULE_RETRY_ATTEMPTS=3", example)
        self.assertNotIn("SNAPSHOT_RETENTION_SCHEDULE_TOKEN", example)
        self.assertNotIn("SNAPSHOT_RETENTION_SCHEDULE_PROVIDER", example)

    def test_korean_operations_doc_defines_bounded_and_redacted_scheduler_contract(self):
        document = (ROOT / "docs" / "EXTERNAL_API_CONFIGURATION.md").read_text(
            encoding="utf-8"
        )

        for required in (
            "기본 비활성",
            "one-shot",
            "동시에 활성화할 수 없습니다",
            "dry-run",
            "최대 10 batches",
            "최대 3 attempts",
            "250ms, 500ms",
            "scheduler thread",
            "provider hard timeout",
            "global exactly-once를 보장하지 않습니다",
            "mode와 outcome",
            "원문 payload",
            "SQL",
            "URL",
            "token",
        ):
            self.assertIn(required, document)


if __name__ == "__main__":
    unittest.main()
