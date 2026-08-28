from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "scripts/run-firebase-compose.sh"
EXPECTED_LAUNCHER = f'''#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
  echo "FCM Compose launcher는 인자를 받지 않습니다." >&2
  exit 2
fi

SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd -P "$SCRIPT_DIR/.." && pwd)

python3 "$ROOT/scripts/validate_firebase_credential_file.py"
exec docker compose \\
  --project-name timing-jeju-fcm \\
  --project-directory "$ROOT" \\
  -f "$ROOT/compose.yml" \\
  -f "$ROOT/compose.fcm.yml" \\
  up -d --build api
'''


class FirebaseComposeLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.temp = Path(self.temporary_directory.name)
        self.fake_bin = self.temp / "bin"
        self.fake_bin.mkdir()
        self.invocation_log = self.temp / "docker-invocations.log"

    def test_launcher는_validator_성공_뒤_fixed_compose를_정확히_한번_실행한다(self):
        self.write_docker_shim()
        credential = self.temp / "firebase.json"
        sensitive_content = "private-key-content-must-not-be-logged"
        credential.write_text(sensitive_content, encoding="utf-8")
        credential.chmod(0o600)

        result = self.run_launcher(credential)

        self.assertEqual(0, result.returncode, result.stderr)
        invocations = self.invocations()
        self.assertEqual(1, len(invocations))
        self.assertEqual(
            "compose "
            "--project-name timing-jeju-fcm "
            f"--project-directory {ROOT} "
            f"-f {ROOT / 'compose.yml'} "
            f"-f {ROOT / 'compose.fcm.yml'} "
            "up -d --build api",
            invocations[0],
        )
        combined_output = result.stdout + result.stderr + "\n".join(invocations)
        self.assertNotIn(str(credential), combined_output)
        self.assertNotIn(sensitive_content, combined_output)

    def test_invalid_credential은_compose를_한번도_호출하지_않는다(self):
        self.write_docker_shim()
        regular = self.temp / "regular.json"
        regular.write_text("test-only-placeholder", encoding="utf-8")
        regular.chmod(0o600)
        group_readable = self.temp / "group-readable.json"
        group_readable.write_text("test-only-placeholder", encoding="utf-8")
        group_readable.chmod(0o640)
        symlink = self.temp / "credential-link.json"
        symlink.symlink_to(regular)

        invalid_paths = (
            self.temp / "missing.json",
            Path("relative-firebase.json"),
            symlink,
            group_readable,
        )
        for path in invalid_paths:
            with self.subTest(path=path):
                result = self.run_launcher(path)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self.invocations())
                combined_output = result.stdout + result.stderr
                self.assertNotIn(str(path), combined_output)
                self.assertNotIn("test-only-placeholder", combined_output)

    def test_argument와_command_environment는_fixed_invocation을_바꾸지_못한다(self):
        self.write_docker_shim()
        credential = self.temp / "firebase.json"
        credential.write_text("test-only-placeholder", encoding="utf-8")
        credential.chmod(0o600)

        rejected = self.run_launcher(credential, "up", "--remove-orphans")
        self.assertEqual(2, rejected.returncode)
        self.assertEqual([], self.invocations())

        accepted = self.run_launcher(
            credential,
            extra_env={
                "DOCKER_COMMAND": "unsafe",
                "COMPOSE_FILE": "unsafe.yml",
                "COMPOSE_PROJECT_NAME": "unsafe",
            },
        )
        self.assertEqual(0, accepted.returncode, accepted.stderr)
        self.assertEqual(1, len(self.invocations()))

    def test_launcher와_documentation은_validator_first_allowlist를_고정한다(self):
        launcher = LAUNCHER.read_text(encoding="utf-8")
        documentation = (ROOT / "docs/FIREBASE_FCM_CONFIGURATION.md").read_text(
            encoding="utf-8"
        )
        self.assert_launcher_contract(launcher, documentation)
        mutations = {
            "validator-deleted": launcher.replace(
                'python3 "$ROOT/scripts/validate_firebase_credential_file.py"\n', "", 1
            ),
            "status-ignored": launcher.replace(
                'python3 "$ROOT/scripts/validate_firebase_credential_file.py"',
                'python3 "$ROOT/scripts/validate_firebase_credential_file.py" || true',
                1,
            ),
            "order-reversed": launcher.replace(
                'python3 "$ROOT/scripts/validate_firebase_credential_file.py"\nexec docker',
                'exec docker\npython3 "$ROOT/scripts/validate_firebase_credential_file.py"',
                1,
            ),
            "variable-command": launcher.replace("exec docker", 'exec "$DOCKER_COMMAND"', 1),
            "argument-forwarding": launcher.replace("up -d --build api", 'up -d "$@"', 1),
        }
        for scenario, mutation in mutations.items():
            with self.subTest(scenario=scenario), self.assertRaises(AssertionError):
                self.assert_launcher_contract(mutation, documentation)

        raw_compose_docs = documentation.replace(
            "./scripts/run-firebase-compose.sh",
            "docker compose -f compose.yml -f compose.fcm.yml up -d --build api",
            1,
        )
        with self.assertRaises(AssertionError):
            self.assert_launcher_contract(launcher, raw_compose_docs)

    def assert_launcher_contract(self, launcher, documentation):
        self.assertEqual(EXPECTED_LAUNCHER, launcher)
        self.assertNotIn("eval ", launcher)
        self.assertNotIn('"$@"', launcher)
        self.assertNotIn("DOCKER_COMMAND", launcher)
        self.assertLess(
            launcher.index("validate_firebase_credential_file.py"),
            launcher.index("exec docker compose"),
        )
        self.assertIn("./scripts/run-firebase-compose.sh", documentation)
        self.assertNotIn(
            "docker compose -f compose.yml -f compose.fcm.yml", documentation
        )

    def run_launcher(self, credential: Path, *arguments: str, extra_env=None):
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": f"{self.fake_bin}{os.pathsep}{environment['PATH']}",
                "FIREBASE_CREDENTIALS_FILE": str(credential),
                "FAKE_DOCKER_LOG": str(self.invocation_log),
            }
        )
        environment.update(extra_env or {})
        return subprocess.run(
            (str(LAUNCHER), *arguments),
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )

    def invocations(self):
        if not self.invocation_log.exists():
            return []
        return self.invocation_log.read_text(encoding="utf-8").splitlines()

    def write_docker_shim(self):
        self.write_executable(
            "docker",
            '''#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
''',
        )

    def write_executable(self, name, content):
        path = self.fake_bin / name
        path.write_text(content, encoding="utf-8")
        path.chmod(0o700)


if __name__ == "__main__":
    unittest.main()
