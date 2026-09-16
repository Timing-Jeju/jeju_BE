"""GCP 배포 기반의 비밀정보와 네트워크 경계를 검사한다."""

from pathlib import Path
import ast
import subprocess
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


if __name__ == "__main__":
    unittest.main()
