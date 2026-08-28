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
        dockerfile = (ROOT / "services/spring-api/Dockerfile").read_text(
            encoding="utf-8"
        )
        documentation = (ROOT / "docs/FIREBASE_FCM_CONFIGURATION.md").read_text(
            encoding="utf-8"
        )

        self.assert_fcm_compose_contract(
            compose, compose_test, env_example, override, documentation
        )
        self.assert_fcm_runtime_image_contract(dockerfile, documentation)
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
                    "/run/secrets/timing-jeju-firebase/service-account.json",
                    "/run/secrets/firebase.json",
                    1,
                ),
                documentation,
            ),
            "init-copy-deleted": (
                compose,
                compose_test,
                env_example,
                override.replace(
                    "        cp /run/secrets/firebase-service-account.json /credential/.service-account.json.tmp\n",
                    "",
                    1,
                ),
                documentation,
            ),
            "init-order-reversed": (
                compose,
                compose_test,
                env_example,
                override.replace(
                    "        cp /run/secrets/firebase-service-account.json /credential/.service-account.json.tmp\n"
                    "        chown 10001:10001 /credential/.service-account.json.tmp\n",
                    "        chown 10001:10001 /credential/.service-account.json.tmp\n"
                    "        cp /run/secrets/firebase-service-account.json /credential/.service-account.json.tmp\n",
                    1,
                ),
                documentation,
            ),
            "init-permission-relaxed": (
                compose,
                compose_test,
                env_example,
                override.replace("chmod 0400", "chmod 0644", 1),
                documentation,
            ),
            "init-chown-deleted": (
                compose,
                compose_test,
                env_example,
                override.replace(
                    "        chown 10001:10001 /credential/.service-account.json.tmp\n",
                    "",
                    1,
                ),
                documentation,
            ),
            "init-chmod-deleted": (
                compose,
                compose_test,
                env_example,
                override.replace(
                    "        chmod 0400 /credential/.service-account.json.tmp\n",
                    "",
                    1,
                ),
                documentation,
            ),
            "init-dependency-deleted": (
                compose,
                compose_test,
                env_example,
                override.replace("        condition: service_completed_successfully\n", "", 1),
                documentation,
            ),
            "api-volume-write-enabled": (
                compose,
                compose_test,
                env_example,
                override.replace("        read_only: true\n", "        read_only: false\n", 1),
                documentation,
            ),
            "api-direct-secret-added": (
                compose,
                compose_test,
                env_example,
                override.replace(
                    "    depends_on:\n",
                    "    secrets:\n"
                    "      - source: firebase-service-account\n"
                    "        target: firebase-service-account.json\n"
                    "    depends_on:\n",
                    1,
                ),
                documentation,
            ),
        }
        for scenario, mutation in mutations.items():
            with self.subTest(scenario=scenario), self.assertRaises(AssertionError):
                self.assert_fcm_compose_contract(*mutation)
        for scenario, mutation in {
            "runtime-uid-deleted": dockerfile.replace("-u 10001", "", 1),
            "runtime-gid-drift": dockerfile.replace("-g 10001", "-g 10002", 1),
        }.items():
            with self.subTest(scenario=scenario), self.assertRaises(AssertionError):
                self.assert_fcm_runtime_image_contract(mutation, documentation)

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

        credential_path = "/run/secrets/timing-jeju-firebase/service-account.json"
        expected_override = f"""services:
  firebase-credential-init:
    image: alpine:3.20.3
    restart: "no"
    user: "0:0"
    command:
      - /bin/sh
      - -eu
      - -c
      - |
        umask 077
        cp /run/secrets/firebase-service-account.json /credential/.service-account.json.tmp
        chown 10001:10001 /credential/.service-account.json.tmp
        chmod 0400 /credential/.service-account.json.tmp
        mv /credential/.service-account.json.tmp /credential/service-account.json
    secrets:
      - source: firebase-service-account
        target: firebase-service-account.json
    volumes:
      - type: volume
        source: firebase-credential
        target: /credential
  api:
    environment:
      FCM_ENABLED: "true"
      GOOGLE_APPLICATION_CREDENTIALS: {credential_path}
    depends_on:
      firebase-credential-init:
        condition: service_completed_successfully
    volumes:
      - type: volume
        source: firebase-credential
        target: /run/secrets/timing-jeju-firebase
        read_only: true

secrets:
  firebase-service-account:
    file: ${{FIREBASE_CREDENTIALS_FILE:?set FIREBASE_CREDENTIALS_FILE}}

volumes:
  firebase-credential:
"""
        self.assertEqual(expected_override, override)
        self.assertIn('      FCM_ENABLED: "true"', override)
        self.assertIn(f"      GOOGLE_APPLICATION_CREDENTIALS: {credential_path}", override)
        init = override.split("  firebase-credential-init:", 1)[1].split("  api:", 1)[0]
        api_override = override.split("  api:", 1)[1].split("\nsecrets:", 1)[0]
        self.assertIn("    secrets:", init)
        self.assertNotIn("    secrets:", api_override)
        self.assertIn("condition: service_completed_successfully", api_override)
        self.assertIn("source: firebase-credential", api_override)
        self.assertIn("read_only: true", api_override)
        self.assertLess(init.index("cp "), init.index("chown 10001:10001"))
        self.assertLess(init.index("chown 10001:10001"), init.index("chmod 0400"))
        self.assertLess(init.index("chmod 0400"), init.index("mv "))
        self.assertNotIn("mode:", override)
        self.assertIn(
            "    file: ${FIREBASE_CREDENTIALS_FILE:?set FIREBASE_CREDENTIALS_FILE}",
            override,
        )
        self.assertIn("compose.fcm.yml", documentation)
        self.assertIn(credential_path, documentation)
        self.assertIn("읽기 전용", documentation)
        self.assertIn("uid/gid/mode", documentation)
        self.assertIn("validate_firebase_credential_file.py", documentation)
        self.assertIn("현재 사용자", documentation)
        self.assertNotIn("sudo chown 10001:10001", documentation)

    def assert_fcm_runtime_image_contract(self, dockerfile, documentation):
        self.assertIn("addgroup -S -g 10001 spring", dockerfile)
        self.assertIn("adduser -S -D -H -u 10001 -G spring spring", dockerfile)
        self.assertIn("USER spring:spring", dockerfile)
        self.assertIn("10001:10001", documentation)


if __name__ == "__main__":
    unittest.main()
