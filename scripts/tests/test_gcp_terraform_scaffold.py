"""GCP 배포 기반의 비밀정보와 네트워크 경계를 검사한다."""

from pathlib import Path
import ast
import json
import os
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2] / "infra/terraform/gcp-be"


class GcpTerraformScaffoldTest(unittest.TestCase):
    def test_private_runtime_and_secret_reference_boundary(self):
        """공인 VM 주소와 Terraform 비밀값 없이 전용 서비스 계정을 사용한다."""
        source = (ROOT / "main.tf").read_text()
        self.assertNotIn("access_config", source)
        self.assertNotIn("secret_data", source)
        self.assertIn("google_secret_manager_secret_iam_member", source)
        self.assertIn("disable_on_destroy = false", source)

    def test_runtime_bootstrap_fails_closed_and_keeps_secrets_in_memory(self):
        """런타임 비밀값은 휘발성 경로에만 두고 실패 시 컨테이너를 시작하지 않는다."""
        source = (ROOT / "templates/startup.sh.tftpl").read_text()
        self.assertIn("set -eu", source)
        self.assertIn("/run/timing-jeju", source)
        self.assertIn("--log-driver=none", source)
        self.assertNotIn("set -x", source)
        self.assertIn("ExecStartPre=", source)

    def test_startup_shell_and_embedded_python_are_valid(self):
        """시작 스크립트와 내장 파이썬의 구문을 외부 실행 없이 검증한다."""
        source = (ROOT / "templates/startup.sh.tftpl").read_text()
        result = subprocess.run(["bash", "-n"], input=source, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        scripts = source.split("<<'PY'\n")[1:]
        self.assertGreaterEqual(len(scripts), 2)
        for script in scripts:
            ast.parse(script.split("\nPY\n", 1)[0])

    def test_failed_secret_preparation_never_calls_docker(self):
        """비밀 조회 실패 시 도커 실행과 원문 오류 노출을 차단한다."""
        source = (ROOT / "templates/startup.sh.tftpl").read_text()
        script = source.split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
        with patch("pathlib.Path.mkdir", side_effect=RuntimeError("secret-canary-value")), patch("subprocess.run") as run, patch("builtins.print") as output:
            with self.assertRaises(SystemExit) as raised:
                exec(compile(script, "startup-prepare", "exec"), {})
        self.assertEqual(raised.exception.code, 1)
        run.assert_not_called()
        self.assertNotIn("secret-canary-value", str(output.call_args_list))

    def test_docker_restart_dependency_and_health_monitor(self):
        """도커 재시작 전파와 본문을 저장하지 않는 장애 감시를 선언한다."""
        source = (ROOT / "templates/startup.sh.tftpl").read_text()
        self.assertIn("PartOf=docker.service", source)
        self.assertIn("WantedBy=multi-user.target docker.service", source)
        monitoring = (ROOT / "monitoring.tf").read_text()
        self.assertIn('google_monitoring_uptime_check_config', monitoring)
        self.assertIn('google_monitoring_alert_policy', monitoring)
        self.assertIn('notification_channels', monitoring)

    def test_metadata_guard_blocks_tokens_but_keeps_vm_dns(self):
        """컨테이너의 메타데이터 토큰 접근은 막되 같은 주소의 VPC DNS는 허용한다."""
        source = (ROOT / "templates/startup.sh.tftpl").read_text()
        self.assertNotRegex(source, r"-d 169\.254\.169\.254/32 -j REJECT")
        match = re.search(r"timing-jeju-metadata-guard <<'SH'\n(.*?)\nSH\n", source, re.S)
        self.assertIsNotNone(match, "metadata guard script is missing")
        with tempfile.TemporaryDirectory() as directory:
            guard = Path(directory) / "guard"
            guard.write_text(match.group(1))
            fake = Path(directory) / "iptables"
            fake.write_text('#!/bin/sh\necho "$*" >> "$IPTABLES_LOG"\n[ "$1" = -C ] && exit "$CHECK_STATUS"\nexit 0\n')
            fake.chmod(0o700)
            log = Path(directory) / "log"
            blocked = [
                "-I DOCKER-USER 1 -d 169.254.169.254/32 -p tcp ! --dport 53 -j REJECT",
                "-I DOCKER-USER 1 -d 169.254.169.254/32 -p udp ! --dport 53 -j REJECT",
            ]
            # 규칙이 없으면 DNS(53)를 제외하고 삽입하고, 이미 있으면 중복 삽입하지 않는다.
            for check_status, expected in (("1", blocked), ("0", [])):
                log.write_text("")
                environment = dict(os.environ, PATH=f"{directory}:{os.environ['PATH']}", IPTABLES_LOG=str(log), CHECK_STATUS=check_status)
                result = subprocess.run(["sh", str(guard)], env=environment, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(sorted(line for line in log.read_text().splitlines() if line.startswith("-I ")), expected)
        self.assertIn("ExecStartPre=/usr/local/sbin/timing-jeju-metadata-guard", source)

    def test_stop_timeout_exceeds_spring_shutdown_phase(self):
        """Spring 정상 종료 단계가 끝나기 전에 Docker/systemd가 강제 종료하지 않는다."""
        source = (ROOT / "templates/startup.sh.tftpl").read_text()
        spring = int(re.search(r"SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=(\d+)s", source).group(1))
        docker = int(re.search(r"docker stop --time=(\d+)", source).group(1))
        systemd = int(re.search(r"TimeoutStopSec=(\d+)", source).group(1))
        self.assertGreaterEqual(docker - spring, 30)
        self.assertGreaterEqual(systemd - docker, 30)

    def test_run_starts_container_from_runtime_snapshot(self):
        """실행 단계는 시작 준비가 검증한 설정 스냅샷의 이미지와 사설 호스트를 사용한다."""
        source = (ROOT / "templates/startup.sh.tftpl").read_text()
        script = source.split("timing-jeju-run <<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
        config = {"image": "registry.example/be@sha256:" + "a" * 64, "hosts": {"mcp.internal": "10.10.1.20"}}
        read_paths = []

        def read_text(path, *args, **kwargs):
            """읽은 경로를 기록하고 설정 스냅샷을 반환한다."""
            read_paths.append(str(path))
            return json.dumps(config)

        with patch("pathlib.Path.read_text", read_text), patch("os.execvp") as execvp:
            exec(compile(script, "timing-jeju-run", "exec"), {})
        self.assertEqual(read_paths, ["/run/timing-jeju/runtime.json"])
        arguments = execvp.call_args.args[1]
        self.assertEqual(arguments[-1], config["image"])
        self.assertIn("mcp.internal:10.10.1.20", arguments)


if __name__ == "__main__":
    unittest.main()
