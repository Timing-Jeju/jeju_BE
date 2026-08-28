import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class SnapshotRetentionSchedulerConfigurationTest(unittest.TestCase):
    def test_saved_place_cleanup_is_separate_and_production_fail_fast(self):
        application = (
            ROOT
            / "services/spring-api/src/main/resources/application.yml"
        ).read_text(encoding="utf-8")
        configuration = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/global/retention"
            / "SavedPlaceRetentionSchedulerConfiguration.java"
        ).read_text(encoding="utf-8")

        self.assertIn("enabled: ${SAVED_PLACE_RETENTION_ENABLED:false}", application)
        self.assertIn("fixed-delay: ${SAVED_PLACE_RETENTION_FIXED_DELAY:PT24H}", application)
        self.assertIn("SecurityRuntimeEnvironmentResolver.resolve(environment)", configuration)
        self.assertIn("SecurityRuntimeEnvironment.PRODUCTION", configuration)
        self.assertNotIn("@Profile", configuration)
        self.assertIn("saved-place retention must be enabled in production", configuration)

    def test_shared_test_and_docker_context_enable_cleanup_with_safe_delay(self):
        test_application = (
            ROOT / "services/spring-api/src/test/resources/application.yml"
        ).read_text(encoding="utf-8")
        compose_test = (ROOT / "compose.test.yml").read_text(encoding="utf-8")

        self.assertIn(
            "saved-place-retention:\n    enabled: true\n    initial-delay: PT24H",
            test_application,
        )
        self.assertIn("SAVED_PLACE_RETENTION_ENABLED: \"true\"", compose_test)
        self.assertIn("SAVED_PLACE_RETENTION_INITIAL_DELAY: PT24H", compose_test)

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
