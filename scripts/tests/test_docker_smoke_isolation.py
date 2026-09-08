from __future__ import annotations

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = (ROOT / "compose.test.yml").read_text(encoding="utf-8")
SHELL_PATH = ROOT / "scripts" / "docker-smoke-test.sh"
POWERSHELL_PATH = ROOT / "scripts" / "docker-smoke-test.ps1"
SHELL = SHELL_PATH.read_text(encoding="utf-8")
POWERSHELL = POWERSHELL_PATH.read_text(encoding="utf-8")


class DockerSmokeIsolationTest(unittest.TestCase):
    def run_shell_with_fake_docker(
        self, *, fail_up: bool = False, fail_all: bool = False, residue: bool = False
    ):
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            invocation_log = temporary_path / "docker.log"
            curl_log = temporary_path / "curl.log"
            docker = temporary_path / "docker"
            curl = temporary_path / "curl"
            docker.write_text(
                "#!/bin/sh\n"
                "printf '%s\\n' \"$*\" >>\"$DOCKER_FAKE_LOG\"\n"
                "if [ \"${DOCKER_FAKE_FAIL_ALL:-0}\" = 1 ]; then exit 23; fi\n"
                "if [ \"${DOCKER_FAKE_FAIL_UP:-0}\" = 1 ] && "
                "printf '%s\\n' \"$*\" | grep -q ' up '; then exit 23; fi\n"
                "case \" $* \" in\n"
                "  *' port api 8080 '*) printf '%s\\n' '127.0.0.1:49152' ;;\n"
                "  *' ps -aq '*) if [ \"${DOCKER_FAKE_RESIDUE:-0}\" = 1 ]; "
                "then printf '%s\\n' 'residual-container'; fi ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            curl.write_text(
                "#!/bin/sh\n"
                "printf '%s\\n' \"$*\" >>\"$CURL_FAKE_LOG\"\n"
                "printf '%s\\n' '{\"status\":\"UP\"}'\n",
                encoding="utf-8",
            )
            docker.chmod(0o755)
            curl.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "PATH": f"{temporary_path}:{environment['PATH']}",
                    "DOCKER_FAKE_LOG": str(invocation_log),
                    "CURL_FAKE_LOG": str(curl_log),
                    "DOCKER_FAKE_FAIL_UP": "1" if fail_up else "0",
                    "DOCKER_FAKE_FAIL_ALL": "1" if fail_all else "0",
                    "DOCKER_FAKE_RESIDUE": "1" if residue else "0",
                }
            )

            completed = subprocess.run(
                [str(SHELL_PATH)],
                cwd=ROOT,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )
            docker_calls = invocation_log.read_text(encoding="utf-8").splitlines()
            curl_calls = (
                curl_log.read_text(encoding="utf-8").splitlines()
                if curl_log.exists()
                else []
            )
            return completed, docker_calls, curl_calls

    def test_compose_lets_docker_allocate_loopback_publish_port(self):
        api_service = COMPOSE.split("  api:", maxsplit=1)[1]

        self.assertIn('host_ip: "127.0.0.1"', api_service)
        self.assertIn("target: 8080", api_service)
        self.assertNotIn("published:", api_service)
        self.assertNotIn("18080", api_service)

    def test_shell_and_powershell_resolve_current_project_publish_port(self):
        self.assertRegex(
            SHELL,
            r'docker compose -p "\$PROJECT" -f compose\.test\.yml port api 8080',
        )
        self.assertRegex(
            POWERSHELL,
            r"docker compose -p \$project -f compose\.test\.yml port api 8080",
        )
        self.assertIn('http://127.0.0.1:${API_PORT}/actuator/health', SHELL)
        self.assertIn('"http://127.0.0.1:$apiPort/actuator/health"', POWERSHELL)
        self.assertNotIn("18080", SHELL)
        self.assertNotIn("18080", POWERSHELL)

    def test_shell_publish_port_parser_fails_closed_at_boundaries(self):
        function_match = re.search(
            r"^resolve_api_port\(\) \{.*?^\}", SHELL, flags=re.MULTILINE | re.DOTALL
        )
        self.assertIsNotNone(function_match, "resolve_api_port 함수가 필요합니다.")
        function = function_match.group(0)

        cases = (
            ("127.0.0.1:49152", 0, "49152"),
            ("", 1, ""),
            ("127.0.0.1:49152\n127.0.0.1:49153", 1, ""),
            ("127.0.0.1:not-a-port", 1, ""),
            ("127.0.0.1:0", 1, ""),
            ("127.0.0.1:65536", 1, ""),
            ("127.0.0.1:99999999999999999999999999999999", 1, ""),
        )
        for published, expected_status, expected_stdout in cases:
            with self.subTest(published=published):
                completed = subprocess.run(
                    ["sh", "-c", f'{function}\nresolve_api_port "$1"', "sh", published],
                    cwd=ROOT,
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(expected_status, completed.returncode)
                self.assertEqual(expected_stdout, completed.stdout.strip())

    def test_powershell_parser_has_the_same_fail_closed_contract(self):
        for contract_fragment in (
            "function Resolve-ApiPort",
            "$entries.Count -ne 1",
            "TryParse",
            "$port -lt 1",
            "$port -gt 65535",
            "[int]::TryParse",
        ):
            with self.subTest(fragment=contract_fragment):
                self.assertIn(contract_fragment, POWERSHELL)

    def test_each_run_uses_a_unique_project_and_project_scoped_cleanup(self):
        self.assertRegex(SHELL, r'PROJECT="timing-jeju-smoke-\$\{RUN_ID\}"')
        self.assertRegex(POWERSHELL, r'\$project = "timing-jeju-smoke-\$runId"')

        for implementation in (SHELL, POWERSHELL):
            with self.subTest(script=implementation[:20]):
                self.assertNotIn('"timing-jeju-smoke"', implementation)
                self.assertNotRegex(
                    implementation, r"docker (?:system|container|volume|image) prune"
                )
                self.assertNotRegex(implementation, r"docker (?:rm|stop|kill)\b")

        self.assertIn(
            'docker compose -p "$PROJECT" -f compose.test.yml down -v --remove-orphans',
            SHELL,
        )
        self.assertIn(
            "docker compose -p $project -f compose.test.yml down -v --remove-orphans",
            POWERSHELL,
        )
        self.assertIn('docker image rm "${PROJECT}-api:latest"', SHELL)
        self.assertIn('docker image rm "${project}-api:latest"', POWERSHELL)
        self.assertIn("trap finish EXIT", SHELL)
        self.assertIn("trap 'exit 130' INT", SHELL)
        self.assertIn("trap 'exit 143' TERM", SHELL)
        self.assertRegex(
            POWERSHELL, r"(?s)try \{.*\} finally \{\s*try \{\s*Cleanup-Smoke"
        )
        self.assertIn("$LASTEXITCODE", POWERSHELL)
        self.assertIn("Assert-NoSmokeResidue", POWERSHELL)

    def test_shell_probes_only_the_publish_port_of_its_current_project(self):
        completed, docker_calls, curl_calls = self.run_shell_with_fake_docker()

        self.assertEqual(1, completed.returncode)
        self.assertIn("audit가 실패하지 않았습니다", completed.stderr)
        project_calls = [call for call in docker_calls if call.startswith("compose -p ")]
        projects = {call.split()[2] for call in project_calls}
        self.assertEqual(1, len(projects))
        project = projects.pop()
        self.assertRegex(project, r"^timing-jeju-smoke-\d{14}-\d+$")
        self.assertIn(
            f"compose -p {project} -f compose.test.yml port api 8080",
            docker_calls,
        )
        self.assertEqual(
            ["--fail --silent http://127.0.0.1:49152/actuator/health"], curl_calls
        )
        self.assertIn(
            f"compose -p {project} -f compose.test.yml down -v --remove-orphans",
            docker_calls,
        )
        self.assertGreaterEqual(
            docker_calls.count(
                f"image ls --filter reference={project}-api:latest --quiet"
            ),
            2,
        )

    def test_compose_up_failure_cleans_only_the_current_project(self):
        completed, docker_calls, curl_calls = self.run_shell_with_fake_docker(
            fail_up=True
        )

        self.assertEqual(23, completed.returncode)
        self.assertEqual([], curl_calls)
        up_call = next(call for call in docker_calls if " up " in f" {call} ")
        project = up_call.split()[2]
        self.assertIn(
            f"compose -p {project} -f compose.test.yml down -v --remove-orphans",
            docker_calls,
        )
        self.assertTrue(
            all(
                call.split()[2] == project
                for call in docker_calls
                if call.startswith("compose -p ")
            )
        )

    def test_exit_handler_blocks_success_when_cleanup_cannot_be_verified(self):
        completed = self.run_shell_exit_handler(status=0, fail_all=True)

        self.assertNotEqual(0, completed.returncode)

    def test_exit_handler_preserves_original_failure_and_interrupt_status(self):
        for original_status in (42, 130):
            with self.subTest(original_status=original_status):
                completed = self.run_shell_exit_handler(
                    status=original_status, fail_all=True
                )
                self.assertEqual(original_status, completed.returncode)

    def test_exit_handler_rejects_current_project_resource_residue(self):
        completed = self.run_shell_exit_handler(status=0, residue=True)

        self.assertNotEqual(0, completed.returncode)

    def run_shell_exit_handler(
        self, *, status: int, fail_all: bool = False, residue: bool = False
    ):
        functions = []
        for function_name in ("cleanup", "finish"):
            function_match = re.search(
                rf"^{function_name}\(\) \{{.*?^\}}",
                SHELL,
                flags=re.MULTILINE | re.DOTALL,
            )
            self.assertIsNotNone(function_match, f"{function_name} 함수가 필요합니다.")
            functions.append(function_match.group(0))

        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            docker = temporary_path / "docker"
            docker.write_text(
                "#!/bin/sh\n"
                "if [ \"${DOCKER_FAKE_FAIL_ALL:-0}\" = 1 ]; then exit 23; fi\n"
                "case \" $* \" in\n"
                "  *' ps -aq '*) if [ \"${DOCKER_FAKE_RESIDUE:-0}\" = 1 ]; "
                "then printf '%s\\n' 'residual-container'; fi ;;\n"
                "  *' image inspect '*) exit 1 ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            docker.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "PATH": f"{temporary_path}:{environment['PATH']}",
                    "DOCKER_FAKE_FAIL_ALL": "1" if fail_all else "0",
                    "DOCKER_FAKE_RESIDUE": "1" if residue else "0",
                }
            )
            empty_databases = " ".join(
                f'{name}=""'
                for name in (
                    "UPGRADE_DB",
                    "HOURS_CONFLICT_DB",
                    "RESULT_DAY_CONFLICT_DB",
                    "RECOMMENDATION_DAY_CONFLICT_DB",
                    "BASE_LINEAGE_CONFLICT_DB",
                    "REFERENCE_CONFLICT_DB",
                    "TIMETABLE_CONFLICT_DB",
                    "OPEN_CLOSED_CONFLICT_DB",
                    "SNAPSHOT_SCOPE_CONFLICT_DB",
                    "CHECKPOINT_STATUS_CONFLICT_DB",
                    "CHECKPOINT_SCOPE_CONFLICT_DB",
                    "UNPARSED_LINEAGE_CONFLICT_DB",
                    "RUN_LINEAGE_CONFLICT_DB",
                    "SOURCE_LINEAGE_CONFLICT_DB",
                    "OPTIONAL_LINEAGE_CONFLICT_DB",
                    "CONCURRENCY_DB",
                )
            )
            command = "\n".join(
                (
                    "set -u",
                    'PROJECT="timing-jeju-smoke-test-1"',
                    empty_databases,
                    'HOURS_CONFLICT_LOG=""',
                    'RESULT_DAY_CONFLICT_LOG=""',
                    'CONSISTENCY_CONFLICT_LOG=""',
                    *functions,
                    "trap finish EXIT",
                    f"exit {status}",
                )
            )
            return subprocess.run(
                ["sh", "-c", command],
                cwd=ROOT,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )


if __name__ == "__main__":
    unittest.main()
