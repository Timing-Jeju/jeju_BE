"""위치 정리 검증의 Docker 명령은 소유 프로세스를 회수하고 제한 시간 안에 끝난다."""

import importlib.util
import os
import signal
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(os.name == "posix", "Unix smoke 명령의 프로세스 그룹 검사")
class DockerCleanupCommandTest(unittest.TestCase):
    def setUp(self):
        path = ROOT / "scripts/docker_cleanup_command.py"
        self.assertTrue(path.is_file(), "bounded cleanup command is missing")
        spec = importlib.util.spec_from_file_location("docker_cleanup_command", path)
        self.module = importlib.util.module_from_spec(spec)
        sys.path.insert(0, str(ROOT / "scripts"))
        try:
            spec.loader.exec_module(self.module)
        finally:
            sys.path.pop(0)

    def test_normal_query_preserves_output(self):
        """정상 조회의 종료 코드와 결과를 그대로 구분한다."""
        status, output = self.module.run([sys.executable, "-c", "print('synthetic-id')"])
        self.assertEqual((0, b"synthetic-id\n"), (status, output))

    def test_nonzero_and_native_124_are_not_recoverable_timeout(self):
        """일반 실패나 도구 자체의 124를 안전하게 회수된 timeout으로 오인하지 않는다."""
        for code in (1, 124):
            with self.subTest(code=code):
                status, output = self.module.run([sys.executable, "-c", f"raise SystemExit({code})"])
                self.assertEqual((1, b""), (status, output))

    def test_timeout_reaps_owned_group_and_preserves_unrelated_process(self):
        """응답이 멈춘 자식 그룹만 회수하고 별도 프로세스에는 신호를 보내지 않는다."""
        sentinel = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"], start_new_session=True)
        try:
            status, output = self.module.run(
                [sys.executable, "-c", "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); time.sleep(60)"],
                timeout=0.5,
            )
            self.assertEqual((124, b""), (status, output))
            self.assertIsNone(sentinel.poll())
        finally:
            sentinel.terminate()
            sentinel.wait(timeout=5)

    def test_oversized_query_is_not_an_empty_success(self):
        """과도한 조회 출력은 빈 잔류량으로 처리하지 않는다."""
        status, output = self.module.run([sys.executable, "-c", "print('x' * 70000)"])
        self.assertEqual((1, b""), (status, output))

    def test_output_limit_stops_a_still_running_command(self):
        """출력이 상한을 넘으면 실행 중인 명령도 회수하고 timeout 복구를 금지한다."""
        status, output = self.module.run([sys.executable, "-c",
            "import time; print('x' * 70000, flush=True); time.sleep(60)"], timeout=2)
        self.assertEqual((1, b""), (status, output))

    def test_parent_exit_does_not_abandon_an_observed_child(self):
        """부모가 먼저 종료해도 관찰한 자식 프로세스를 회수한다."""
        with tempfile.TemporaryDirectory() as directory:
            pid_path = Path(directory) / "child.pid"
            command = "import subprocess,sys,time; from pathlib import Path; child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); Path(sys.argv[1]).write_text(str(child.pid)); time.sleep(0.3)"
            status, _ = self.module.run([sys.executable, "-c", command, str(pid_path)])
            child_pid = pid_path.read_text()
            probe = subprocess.run(["ps", "-p", child_pid, "-o", "stat="], capture_output=True, text=True)
            try:
                self.assertEqual(1, status)
                self.assertTrue(probe.returncode != 0 or probe.stdout.strip().startswith("Z"), "observed child was left running")
            finally:
                if probe.returncode == 0 and not probe.stdout.strip().startswith("Z"):
                    # Synthetic child belongs to this test; release the RED fixture.
                    os.kill(int(child_pid), signal.SIGTERM)
