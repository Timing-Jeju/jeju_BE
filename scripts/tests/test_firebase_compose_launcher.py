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
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" stop api
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" rm -f api
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

if docker container inspect timing-jeju-fcm-api-1 >/dev/null 2>&1; then
  docker container rm -f timing-jeju-fcm-api-1 >/dev/null
fi
if docker container inspect timing-jeju-fcm-firebase-credential-init-1 >/dev/null 2>&1; then
  docker container rm -f timing-jeju-fcm-firebase-credential-init-1 >/dev/null
fi
if docker volume inspect timing-jeju-fcm_firebase-credential >/dev/null 2>&1; then
  docker volume rm timing-jeju-fcm_firebase-credential >/dev/null
fi
"""


class FirebaseComposeLauncherTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.temp = Path(self.directory.name)
        self.fake_bin = self.temp / "bin"
        self.fake_bin.mkdir()
        self.log = self.temp / "docker.log"
        self.api_state = self.temp / "api.state"
        self.api_marker = self.temp / "api.exists"
        self.postgres_state = self.temp / "postgres.state"
        self.init_marker = self.temp / "init.exists"
        self.volume_marker = self.temp / "volume.exists"
        shim = self.fake_bin / "docker"
        shim.write_text("""#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
case "$*" in
  *" stop api") printf stopped > "$FAKE_API_STATE" ;;
  *" rm -f api") printf removed > "$FAKE_API_STATE" ;;
  *" up -d --build --force-recreate --no-deps api") printf running > "$FAKE_API_STATE" ;;
  "container inspect timing-jeju-fcm-api-1") [ -f "$FAKE_API_MARKER" ] || exit 1 ;;
  "container inspect timing-jeju-fcm-firebase-credential-init-1") [ -f "$FAKE_INIT_MARKER" ] || exit 1 ;;
  "volume inspect timing-jeju-fcm_firebase-credential") [ -f "$FAKE_VOLUME_MARKER" ] || exit 1 ;;
  "container rm -f timing-jeju-fcm-api-1") rm -f "$FAKE_API_MARKER" ;;
  "container rm -f timing-jeju-fcm-firebase-credential-init-1") rm -f "$FAKE_INIT_MARKER" ;;
  "volume rm timing-jeju-fcm_firebase-credential") rm -f "$FAKE_VOLUME_MARKER" ;;
esac
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
        self.api_state.write_text("running", encoding="utf-8")
        self.postgres_state.write_text("postgres-identity-A", encoding="utf-8")
        result = self.run_script(LAUNCHER, self.credential())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            f"{PREFIX} stop api",
            f"{PREFIX} rm -f api",
            f"{PREFIX} up -d --build postgres",
            f"{PREFIX} up -d --force-recreate firebase-credential-init",
            f"{PREFIX} wait firebase-credential-init",
            f"{PREFIX} up -d --build --force-recreate --no-deps api",
        ], self.invocations())
        self.assertEqual("running", self.api_state.read_text(encoding="utf-8"))
        self.assertEqual("postgres-identity-A", self.postgres_state.read_text(encoding="utf-8"))
        self.assertNotIn("private-key-content-must-not-be-logged", result.stdout + result.stderr)

    def test_init_failure_prevents_api(self):
        self.api_state.write_text("running", encoding="utf-8")
        result = self.run_script(LAUNCHER, self.credential(),
                          extra_env={"FAKE_FAIL_ON": "wait firebase-credential-init"})
        self.assertEqual(42, result.returncode)
        self.assertEqual([
            f"{PREFIX} stop api",
            f"{PREFIX} rm -f api",
            f"{PREFIX} up -d --build postgres",
            f"{PREFIX} up -d --force-recreate firebase-credential-init",
            f"{PREFIX} wait firebase-credential-init",
        ], self.invocations())
        self.assertEqual("removed", self.api_state.read_text(encoding="utf-8"))

    def test_invalid_credential_and_args_invoke_nothing(self):
        self.api_state.write_text("running", encoding="utf-8")
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
                self.assertEqual("running", self.api_state.read_text(encoding="utf-8"))
        self.assertEqual(2, self.run_script(LAUNCHER, self.credential(), "unsafe").returncode)
        self.assertEqual([], self.invocations())

    def test_cleanup_exact_targets(self):
        for marker in (self.api_marker, self.init_marker, self.volume_marker):
            marker.touch()
        self.postgres_state.write_text("postgres-identity-A", encoding="utf-8")
        result = self.run_script(CLEANUP, None)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            "container inspect timing-jeju-fcm-api-1",
            "container rm -f timing-jeju-fcm-api-1",
            "container inspect timing-jeju-fcm-firebase-credential-init-1",
            "container rm -f timing-jeju-fcm-firebase-credential-init-1",
            "volume inspect timing-jeju-fcm_firebase-credential",
            "volume rm timing-jeju-fcm_firebase-credential",
        ], self.invocations())
        self.log.unlink()
        second = self.run_script(CLEANUP, None)
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual([
            "container inspect timing-jeju-fcm-api-1",
            "container inspect timing-jeju-fcm-firebase-credential-init-1",
            "volume inspect timing-jeju-fcm_firebase-credential",
        ], self.invocations())
        self.assertEqual("postgres-identity-A", self.postgres_state.read_text(encoding="utf-8"))

    def test_cleanup_zero_arg_injection_safe_and_fail_closed(self):
        self.assertEqual(2, self.run_script(CLEANUP, None, "postgres").returncode)
        self.assertEqual([], self.invocations())
        self.api_marker.touch()
        result = self.run_script(CLEANUP, None, extra_env={
            "DOCKER_COMMAND": "unsafe", "COMPOSE_FILE": "unsafe",
            "COMPOSE_PROJECT_NAME": "unsafe", "FAKE_FAIL_ON": "container rm"})
        self.assertEqual(42, result.returncode)
        self.assertEqual(["container inspect timing-jeju-fcm-api-1", "container rm -f timing-jeju-fcm-api-1"], self.invocations())

    def test_source_contract_and_mutations(self):
        launcher = LAUNCHER.read_text(encoding="utf-8")
        cleanup = CLEANUP.read_text(encoding="utf-8")
        docs = (ROOT / "docs/FIREBASE_FCM_CONFIGURATION.md").read_text(encoding="utf-8")
        self.assert_contract(launcher, cleanup, docs)
        postgres_step = 'up -d --build postgres\n'
        init_step = 'up -d --force-recreate firebase-credential-init\n'
        stop_step = 'stop api\n'
        mutations = [
            (launcher.replace('python3 "$ROOT/scripts/validate_firebase_credential_file.py"\n', "", 1), cleanup),
            (launcher.replace("wait firebase-credential-init\n", "", 1), cleanup),
            (launcher.replace("--force-recreate --no-deps api", "--no-deps api", 1), cleanup),
            (launcher.replace("--force-recreate --no-deps api", "--force-recreate api", 1), cleanup),
            (launcher.replace("up -d --build postgres\n", "", 1), cleanup),
            (launcher.replace(stop_step, "", 1), cleanup),
            (launcher.replace(stop_step, "ORDER_SENTINEL\n", 1)
                     .replace(init_step, stop_step, 1)
                     .replace("ORDER_SENTINEL\n", init_step, 1), cleanup),
            (launcher.replace(postgres_step, "ORDER_SENTINEL\n", 1)
                     .replace(init_step, postgres_step, 1)
                     .replace("ORDER_SENTINEL\n", init_step, 1), cleanup),
            (launcher.replace("exec docker", 'exec "$DOCKER_COMMAND"', 1), cleanup),
            (launcher.replace("api\n", '"$@"\n', 1), cleanup),
            (launcher, cleanup.replace("timing-jeju-fcm-api-1", "timing-jeju-fcm-postgres-1", 1)),
            (launcher, cleanup.replace("docker volume rm timing-jeju-fcm_firebase-credential >/dev/null\n", "", 1)),
            (launcher, cleanup.replace("docker container rm -f", "docker container rm -f timing-jeju-fcm-postgres-1", 1)),
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
                    "FAKE_DOCKER_LOG": str(self.log), "FAKE_FAIL_ON": "",
                    "FAKE_API_STATE": str(self.api_state), "FAKE_API_MARKER": str(self.api_marker),
                    "FAKE_INIT_MARKER": str(self.init_marker), "FAKE_VOLUME_MARKER": str(self.volume_marker)})
        if credential is None:
            env.pop("FIREBASE_CREDENTIALS_FILE", None)
        else:
            env["FIREBASE_CREDENTIALS_FILE"] = str(credential)
        env.update(extra_env or {})
        return subprocess.run((str(script), *args), cwd=ROOT, env=env,
                              capture_output=True, text=True, check=False)

    def invocations(self):
        return self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []


if __name__ == "__main__":
    unittest.main()
