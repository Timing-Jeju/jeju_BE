from __future__ import annotations

import subprocess
import unittest

from scripts.validate_docker_compose_version import ComposeVersionError
from scripts.validate_docker_compose_version import parse_compose_version
from scripts.validate_docker_compose_version import validate_installed_compose


class DockerComposeVersionPreflightTest(unittest.TestCase):
    def test_minimum_and_newer_versions_are_accepted(self):
        for raw in ("2.24.4", "v2.24.4", "2.25.0", "5.3.1"):
            with self.subTest(raw=raw):
                self.assertGreaterEqual(parse_compose_version(raw), (2, 24, 4))

    def test_older_malformed_nonfinite_or_decorated_versions_are_rejected(self):
        for raw in ("2.24.3", "v2.24", "NaN", "Infinity", "2.24.4-beta.1", "2.24.4 extra", ""):
            with self.subTest(raw=raw), self.assertRaises(ComposeVersionError):
                parse_compose_version(raw)

    def test_validator_invokes_only_exact_sanitized_command(self):
        calls = []

        def run(command, **kwargs):
            calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, 0, stdout="v2.24.4\n", stderr="")

        validate_installed_compose(run=run)
        self.assertEqual(("docker", "compose", "version", "--short"), calls[0][0])
        self.assertTrue(calls[0][1]["capture_output"])
        self.assertTrue(calls[0][1]["text"])
        self.assertFalse(calls[0][1]["check"])

    def test_command_failure_is_generic_and_does_not_expose_raw_output(self):
        sensitive = "raw-daemon-detail-must-not-leak"

        def run(command, **kwargs):
            return subprocess.CompletedProcess(command, 42, stdout=sensitive, stderr=sensitive)

        with self.assertRaisesRegex(ComposeVersionError, "확인할 수 없습니다") as captured:
            validate_installed_compose(run=run)
        self.assertNotIn(sensitive, str(captured.exception))


if __name__ == "__main__":
    unittest.main()
