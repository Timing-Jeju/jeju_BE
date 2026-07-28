from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "scan-staged-secrets.py"
SPEC = importlib.util.spec_from_file_location("scan_staged_secrets", MODULE_PATH)
assert SPEC is not None
assert SPEC.loader is not None
scan_staged_secrets = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scan_staged_secrets)


class ContainsSecretTest(unittest.TestCase):
    def test_spring_environment_placeholder_with_default_is_allowed(self):
        configuration = (
            "password: ${SPRING_DATASOURCE_PASSWORD:timing_jeju}\n"
            "api-key: ${EXTERNAL_API_KEY:sk-example}\n"
        )

        self.assertFalse(scan_staged_secrets.contains_secret(configuration))

    def test_empty_yaml_password_does_not_consume_the_next_line(self):
        configuration = "password:" + "\ndriver-class-name: org.h2.Driver\n"

        self.assertFalse(scan_staged_secrets.contains_secret(configuration))

    def test_real_shaped_bearer_token_is_blocked(self):
        synthetic_token = "AbCdEf1234567890" + "AbCdEf1234567890"

        self.assertTrue(
            scan_staged_secrets.contains_secret(
                "Authorization: Bearer " + synthetic_token
            )
        )

    def test_real_shaped_secret_in_environment_default_is_blocked(self):
        synthetic_token = "sk-proj-" + ("AbCdEf1234567890" * 2)

        self.assertTrue(
            scan_staged_secrets.contains_secret(
                "api-key: ${EXTERNAL_API_KEY:" + synthetic_token + "}"
            )
        )


if __name__ == "__main__":
    unittest.main()
