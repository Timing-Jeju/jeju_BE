from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class Issue114DevelopIntegrationTest(unittest.TestCase):
    def test_firebase_adapter와_mode20_contract가_함께_유지된다(self):
        firebase_adapter = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/global/push/firebase"
            / "FirebasePushMessageSender.java"
        )
        self.assertTrue(firebase_adapter.is_file())

        for gate_name in ("quality-gate.sh", "quality-gate.ps1"):
            gate = (ROOT / "scripts" / gate_name).read_text(encoding="utf-8")
            self.assertIn("--mode 20", gate, gate_name)
            self.assertNotIn("--mode 16", gate, gate_name)

        migration_names = {
            path.name for path in (ROOT / "supabase/migrations").glob("*.sql")
        }
        self.assertIn(
            "20260904000000_push_device_notification_preferences.sql",
            migration_names,
        )
        self.assertIn(
            "20260904000001_push_notification_server_writer_boundary.sql",
            migration_names,
        )

    def test_fcm_compose_runtime과_ADC_secret_boundary가_fail_closed이다(self):
        compose = (ROOT / "compose.yml").read_text(encoding="utf-8")
        compose_test = (ROOT / "compose.test.yml").read_text(encoding="utf-8")
        env_example = (ROOT / ".env.example").read_text(encoding="utf-8")
        override = (ROOT / "compose.fcm.yml").read_text(encoding="utf-8")
        documentation = (ROOT / "docs/FIREBASE_FCM_CONFIGURATION.md").read_text(
            encoding="utf-8"
        )

        self.assert_fcm_compose_contract(
            compose, compose_test, env_example, override, documentation
        )
        mutations = {
            "runtime-disabled-default-deleted": (
                compose.replace("      FCM_ENABLED: ${FCM_ENABLED:-false}\n", "", 1),
                compose_test,
                env_example,
                override,
                documentation,
            ),
            "runtime-project-id-typo": (
                compose.replace("FIREBASE_PROJECT_ID", "FIREBASE_PROJECT", 1),
                compose_test,
                env_example,
                override,
                documentation,
            ),
            "runtime-read-timeout-deleted": (
                compose.replace(
                    "      FCM_READ_TIMEOUT: ${FCM_READ_TIMEOUT:-5s}\n", "", 1
                ),
                compose_test,
                env_example,
                override,
                documentation,
            ),
            "test-enables-provider": (
                compose,
                compose_test.replace('FCM_ENABLED: "false"', 'FCM_ENABLED: "true"', 1),
                env_example,
                override,
                documentation,
            ),
            "env-example-credential-path-deleted": (
                compose,
                compose_test,
                env_example.replace("FIREBASE_CREDENTIALS_FILE=\n", "", 1),
                override,
                documentation,
            ),
            "env-example-credential-path-typo": (
                compose,
                compose_test,
                env_example.replace(
                    "FIREBASE_CREDENTIALS_FILE=", "FIREBASE_CREDENTIAL_FILE=", 1
                ),
                override,
                documentation,
            ),
            "secret-source-deleted": (
                compose,
                compose_test,
                env_example,
                override.replace(
                    "    file: ${FIREBASE_CREDENTIALS_FILE:?set FIREBASE_CREDENTIALS_FILE}\n",
                    "",
                    1,
                ),
                documentation,
            ),
            "credential-target-typo": (
                compose,
                compose_test,
                env_example,
                override.replace(
                    "timing-jeju-firebase-service-account.json", "firebase.json", 1
                ),
                documentation,
            ),
        }
        for scenario, mutation in mutations.items():
            with self.subTest(scenario=scenario), self.assertRaises(AssertionError):
                self.assert_fcm_compose_contract(*mutation)

    def assert_fcm_compose_contract(
        self, compose, compose_test, env_example, override, documentation
    ):
        api = compose.split("  api:", 1)[1].split("    ports:", 1)[0]
        expected_runtime = (
            "      FCM_ENABLED: ${FCM_ENABLED:-false}",
            "      FIREBASE_PROJECT_ID: ${FIREBASE_PROJECT_ID:-}",
            "      FCM_CONNECT_TIMEOUT: ${FCM_CONNECT_TIMEOUT:-2s}",
            "      FCM_READ_TIMEOUT: ${FCM_READ_TIMEOUT:-5s}",
            "      FCM_WRITE_TIMEOUT: ${FCM_WRITE_TIMEOUT:-5s}",
        )
        for line in expected_runtime:
            self.assertIn(line, api)

        test_api = compose_test.split("  api:", 1)[1].split("    ports:", 1)[0]
        self.assertIn('      FCM_ENABLED: "false"', test_api)
        self.assertNotIn('      FCM_ENABLED: "true"', test_api)
        self.assertNotIn("GOOGLE_APPLICATION_CREDENTIALS", test_api)

        env_lines = set(env_example.splitlines())
        for line in (
            "FCM_ENABLED=false",
            "FIREBASE_PROJECT_ID=",
            "FCM_CONNECT_TIMEOUT=2s",
            "FCM_READ_TIMEOUT=5s",
            "FCM_WRITE_TIMEOUT=5s",
            "FIREBASE_CREDENTIALS_FILE=",
        ):
            self.assertIn(line, env_lines)

        credential_path = "/run/secrets/timing-jeju-firebase-service-account.json"
        expected_override = f"""services:
  api:
    environment:
      FCM_ENABLED: "true"
      GOOGLE_APPLICATION_CREDENTIALS: {credential_path}
    secrets:
      - source: firebase-service-account
        target: timing-jeju-firebase-service-account.json
        mode: 0400

secrets:
  firebase-service-account:
    file: ${{FIREBASE_CREDENTIALS_FILE:?set FIREBASE_CREDENTIALS_FILE}}
"""
        self.assertEqual(expected_override, override)
        self.assertIn('      FCM_ENABLED: "true"', override)
        self.assertIn(f"      GOOGLE_APPLICATION_CREDENTIALS: {credential_path}", override)
        self.assertIn("    - source: firebase-service-account", override)
        self.assertIn("      target: timing-jeju-firebase-service-account.json", override)
        self.assertIn("      mode: 0400", override)
        self.assertIn(
            "    file: ${FIREBASE_CREDENTIALS_FILE:?set FIREBASE_CREDENTIALS_FILE}",
            override,
        )
        self.assertIn("compose.fcm.yml", documentation)
        self.assertIn(credential_path, documentation)
        self.assertIn("읽기 전용", documentation)


if __name__ == "__main__":
    unittest.main()
