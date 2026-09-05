from __future__ import annotations

import socket
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts" / "validate_smoke_api_port.py"
UNIX_SMOKE = ROOT / "scripts" / "docker-smoke-test.sh"
WINDOWS_SMOKE = ROOT / "scripts" / "docker-smoke-test.ps1"
COMPOSE = ROOT / "compose.test.yml"


class DockerSmokePortContractTest(unittest.TestCase):
    def test_validator_accepts_loopback_port_and_rejects_invalid_ranges(self) -> None:
        valid = self.run_validator("28080")
        self.assertEqual(0, valid.returncode, valid.stderr)
        self.assertEqual("28080", valid.stdout.strip())

        for value in ("", "abc", "1.5", "1023", "65536", "-1"):
            with self.subTest(value=value):
                result = self.run_validator(value)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("Traceback", result.stderr)

    def test_validator_fails_closed_when_loopback_port_is_occupied(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            port = listener.getsockname()[1]

            result = self.run_validator(str(port))

        self.assertNotEqual(0, result.returncode)
        self.assertIn("already in use", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_compose_publishes_configured_port_on_loopback_only(self) -> None:
        compose = COMPOSE.read_text(encoding="utf-8")

        self.assertIn(
            '"127.0.0.1:${TIMING_JEJU_SMOKE_API_PORT:-28080}:8080"', compose
        )
        self.assertNotIn('"18080:8080"', compose)
        self.assertIn("http://127.0.0.1:8080/actuator/health", compose)

    def test_unix_smoke_validates_and_uses_same_explicit_port(self) -> None:
        smoke = UNIX_SMOKE.read_text(encoding="utf-8")

        validation = smoke.index("validate_smoke_api_port.py")
        compose_up = smoke.index('docker compose -p "$PROJECT" -f compose.test.yml up')
        self.assertLess(validation, compose_up)
        self.assertIn(
            "SMOKE_API_PORT=${TIMING_JEJU_SMOKE_API_PORT:-28080}", smoke
        )
        self.assertIn("export TIMING_JEJU_SMOKE_API_PORT=$SMOKE_API_PORT", smoke)
        self.assertIn(
            'http://127.0.0.1:$SMOKE_API_PORT/actuator/health', smoke
        )
        self.assertNotIn("http://127.0.0.1:18080/actuator/health", smoke)

    def test_windows_smoke_has_matching_validation_and_health_boundary(self) -> None:
        smoke = WINDOWS_SMOKE.read_text(encoding="utf-8")

        validation = smoke.index("validate_smoke_api_port.py")
        compose_up = smoke.index("docker compose -p $project -f compose.test.yml up")
        self.assertLess(validation, compose_up)
        self.assertIn('if ($env:TIMING_JEJU_SMOKE_API_PORT)', smoke)
        self.assertIn('"28080"', smoke)
        self.assertIn("$env:TIMING_JEJU_SMOKE_API_PORT = $smokeApiPort", smoke)
        self.assertIn(
            '"http://127.0.0.1:$smokeApiPort/actuator/health"', smoke
        )
        self.assertNotIn("http://127.0.0.1:18080/actuator/health", smoke)

    def test_mutations_cannot_remove_validation_or_loopback_binding(self) -> None:
        unix = UNIX_SMOKE.read_text(encoding="utf-8")
        windows = WINDOWS_SMOKE.read_text(encoding="utf-8")
        compose = COMPOSE.read_text(encoding="utf-8")
        mutations = (
            unix.replace("validate_smoke_api_port.py", "removed-validator.py", 1),
            windows.replace("validate_smoke_api_port.py", "removed-validator.py", 1),
            compose.replace("127.0.0.1:", "", 1),
            compose.replace("TIMING_JEJU_SMOKE_API_PORT", "UNSAFE_PORT", 1),
        )

        for mutation in mutations[:2]:
            with self.assertRaises(AssertionError):
                self.assertIn("validate_smoke_api_port.py", mutation)
        for mutation in mutations[2:]:
            with self.assertRaises(AssertionError):
                self.assertIn(
                    '"127.0.0.1:${TIMING_JEJU_SMOKE_API_PORT:-28080}:8080"',
                    mutation,
                )

    def run_validator(self, value: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(VALIDATOR), value],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )


if __name__ == "__main__":
    unittest.main()
