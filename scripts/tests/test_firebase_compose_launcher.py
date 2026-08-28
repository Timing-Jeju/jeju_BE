from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "scripts/run-firebase-compose.sh"
CLEANUP = ROOT / "scripts/cleanup-firebase-compose.sh"
PREFIX = ("compose --project-name timing-jeju-fcm "
          f"--project-directory {ROOT} -f {ROOT / 'compose.yml'} -f {ROOT / 'compose.fcm.yml'}")
EXPECTED_LAUNCHER = f"""#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
  echo "FCM Compose launcher는 인자를 받지 않습니다." >&2
  exit 2
fi

SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd -P "$SCRIPT_DIR/.." && pwd)

python3 "$ROOT/scripts/validate_firebase_credential_file.py"
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" up -d --build postgres
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" up -d --force-recreate firebase-credential-init
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" wait firebase-credential-init
exec docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" up -d --build --force-recreate --no-deps api
"""
EXPECTED_CLEANUP = f"""#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
  echo "FCM Compose cleanup은 인자를 받지 않습니다." >&2
  exit 2
fi

SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd -P "$SCRIPT_DIR/.." && pwd)

docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" stop api firebase-credential-init
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" rm -f api firebase-credential-init
exec docker volume rm timing-jeju-fcm_firebase-credential
"""


class FirebaseComposeLauncherTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.temp = Path(self.directory.name)
        self.fake_bin = self.temp / "bin"
        self.fake_bin.mkdir()
        self.log = self.temp / "docker.log"
        shim = self.fake_bin / "docker"
        shim.write_text("""#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
if [ -n "$FAKE_FAIL_ON" ]; then
  case "$*" in *"$FAKE_FAIL_ON"*) exit 42 ;; esac
fi
""", encoding="utf-8")
        shim.chmod(0o700)

    def credential(self):
        path = self.temp / "firebase.json"
        path.write_text("private-key-content-must-not-be-logged", encoding="utf-8")
        path.chmod(0o600)
        return path

    def test_launch_lifecycle_exact_order(self):
        result = self.run_script(LAUNCHER, self.credential())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            f"{PREFIX} up -d --build postgres",
            f"{PREFIX} up -d --force-recreate firebase-credential-init",
            f"{PREFIX} wait firebase-credential-init",
            f"{PREFIX} up -d --build --force-recreate --no-deps api",
        ], self.invocations())
        self.assertNotIn("private-key-content-must-not-be-logged", result.stdout + result.stderr)

    def test_init_failure_prevents_api(self):
        result = self.run_script(LAUNCHER, self.credential(),
                          extra_env={"FAKE_FAIL_ON": "wait firebase-credential-init"})
        self.assertEqual(42, result.returncode)
        self.assertEqual([
            f"{PREFIX} up -d --build postgres",
            f"{PREFIX} up -d --force-recreate firebase-credential-init",
            f"{PREFIX} wait firebase-credential-init",
        ], self.invocations())

    def test_invalid_credential_and_args_invoke_nothing(self):
        regular = self.credential()
        group_readable = self.temp / "group-readable.json"
        group_readable.write_text("test-only-placeholder", encoding="utf-8")
        group_readable.chmod(0o640)
        symlink = self.temp / "credential-link.json"
        symlink.symlink_to(regular)
        for invalid in (self.temp / "missing", Path("relative.json"), symlink, group_readable):
            with self.subTest(invalid=invalid):
                self.assertNotEqual(0, self.run_script(LAUNCHER, invalid).returncode)
                self.assertEqual([], self.invocations())
        self.assertEqual(2, self.run_script(LAUNCHER, self.credential(), "unsafe").returncode)
        self.assertEqual([], self.invocations())

    def test_cleanup_exact_targets(self):
        result = self.run_script(CLEANUP, self.credential())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            f"{PREFIX} stop api firebase-credential-init",
            f"{PREFIX} rm -f api firebase-credential-init",
            "volume rm timing-jeju-fcm_firebase-credential",
        ], self.invocations())

    def test_cleanup_zero_arg_injection_safe_and_fail_closed(self):
        self.assertEqual(2, self.run_script(CLEANUP, self.credential(), "postgres").returncode)
        self.assertEqual([], self.invocations())
        result = self.run_script(CLEANUP, self.credential(), extra_env={
            "DOCKER_COMMAND": "unsafe", "COMPOSE_FILE": "unsafe",
            "COMPOSE_PROJECT_NAME": "unsafe", "FAKE_FAIL_ON": "stop api"})
        self.assertEqual(42, result.returncode)
        self.assertEqual([f"{PREFIX} stop api firebase-credential-init"], self.invocations())

    def test_source_contract_and_mutations(self):
        launcher = LAUNCHER.read_text(encoding="utf-8")
        cleanup = CLEANUP.read_text(encoding="utf-8")
        docs = (ROOT / "docs/FIREBASE_FCM_CONFIGURATION.md").read_text(encoding="utf-8")
        self.assert_contract(launcher, cleanup, docs)
        postgres_step = 'up -d --build postgres\n'
        init_step = 'up -d --force-recreate firebase-credential-init\n'
        mutations = [
            (launcher.replace('python3 "$ROOT/scripts/validate_firebase_credential_file.py"\n', "", 1), cleanup),
            (launcher.replace("wait firebase-credential-init\n", "", 1), cleanup),
            (launcher.replace("--force-recreate --no-deps api", "--no-deps api", 1), cleanup),
            (launcher.replace("--force-recreate --no-deps api", "--force-recreate api", 1), cleanup),
            (launcher.replace("up -d --build postgres\n", "", 1), cleanup),
            (launcher.replace(postgres_step, "ORDER_SENTINEL\n", 1)
                     .replace(init_step, postgres_step, 1)
                     .replace("ORDER_SENTINEL\n", init_step, 1), cleanup),
            (launcher.replace("exec docker", 'exec "$DOCKER_COMMAND"', 1), cleanup),
            (launcher.replace("api\n", '"$@"\n', 1), cleanup),
            (launcher, cleanup.replace("api firebase-credential-init", "api firebase-credential-init postgres", 1)),
            (launcher, cleanup.replace("exec docker volume rm timing-jeju-fcm_firebase-credential\n", "", 1)),
            (launcher, cleanup.replace("stop api firebase-credential-init", "down -v", 1)),
        ]
        for launch_mutation, cleanup_mutation in mutations:
            with self.assertRaises(AssertionError):
                self.assert_contract(launch_mutation, cleanup_mutation, docs)

    def assert_contract(self, launcher, cleanup, docs):
        self.assertEqual(EXPECTED_LAUNCHER, launcher)
        self.assertEqual(EXPECTED_CLEANUP, cleanup)
        for source in (launcher, cleanup):
            self.assertNotIn("eval ", source)
            self.assertNotIn('"$@"', source)
            self.assertNotIn("DOCKER_COMMAND", source)
            self.assertNotIn(" down", source)
            self.assertNotIn(" -v", source)
        self.assertIn("./scripts/run-firebase-compose.sh", docs)
        self.assertIn("./scripts/cleanup-firebase-compose.sh", docs)
        self.assertNotIn("docker compose -f compose.yml -f compose.fcm.yml", docs)

    def run_script(self, script, credential, *args, extra_env=None):
        env = os.environ.copy()
        env.update({"PATH": f"{self.fake_bin}{os.pathsep}{env['PATH']}",
                    "FIREBASE_CREDENTIALS_FILE": str(credential),
                    "FAKE_DOCKER_LOG": str(self.log), "FAKE_FAIL_ON": ""})
        env.update(extra_env or {})
        return subprocess.run((str(script), *args), cwd=ROOT, env=env,
                              capture_output=True, text=True, check=False)

    def invocations(self):
        return self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []


if __name__ == "__main__":
    unittest.main()
