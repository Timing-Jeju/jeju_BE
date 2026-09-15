"""Docker 정리 timeout은 전체 잔류 조회가 입증된 경우에만 복구한다."""

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(os.name == "posix", "Unix smoke 정리의 shell 복구 검사")
class DockerCleanupRecoveryTest(unittest.TestCase):
    def run_cleanup(self, *, residue="", query_failure="", removal_status=124, original_status=0):
        source = (ROOT / "scripts/docker-smoke-test.sh").read_text()
        cleanup = source.split("\ncleanup() {", 1)[1].split("\n}\n", 1)[0]
        finish = source.split("\nfinish() {", 1)[1].split("\n}\n", 1)[0]
        definitions = "\n".join(f'{key}=""' for key in sorted(set(re.findall(r"\$\{?([A-Z][A-Z0-9_]*)", cleanup))))
        fake = r'''
bounded_cleanup() {
  case "$2 $3" in
    'image rm') touch "$REMOVED"; return "$REMOVAL_STATUS" ;;
    'image ls')
      if [ ! -f "$REMOVED" ]; then echo synthetic-image; return 0; fi
      kind=image ;;
    'network ls') kind=network ;;
    'volume ls') kind=volume ;;
    'compose -p')
      case "$*" in *' ps -aq') kind=container ;; *) return 0 ;; esac ;;
    *) return 91 ;;
  esac
  if [ "$QUERY_FAILURE" = "$kind" ]; then return 124; fi
  if [ "$RESIDUE" = "$kind" ]; then echo synthetic-residue; fi
  return 0
}
'''
        script = definitions + "\n" + fake + "\ncleanup() {" + cleanup + "\n}\nfinish() {" + finish + "\n}\n"
        script += '(exit "$ORIGINAL_STATUS")\nfinish\n'
        with tempfile.TemporaryDirectory() as directory:
            environment = dict(os.environ, REMOVED=str(Path(directory) / "removed"),
                RESIDUE=residue, QUERY_FAILURE=query_failure,
                REMOVAL_STATUS=str(removal_status), ORIGINAL_STATUS=str(original_status))
            return subprocess.run(["sh", "-c", script], env=environment, text=True,
                capture_output=True, timeout=10)

    def test_timeout_recovers_only_after_all_successful_empty_queries(self):
        """삭제 timeout 이후 모든 자원 조회가 성공하고 비어 있으면 복구를 표시한다."""
        result = self.run_cleanup()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("timeout 복구", result.stdout)

    def test_any_residue_or_unknown_query_blocks_recovery(self):
        """어느 자원이든 잔류하거나 조회가 실패하면 성공으로 간주하지 않는다."""
        for kind in ("container", "network", "volume", "image"):
            for failure in (False, True):
                with self.subTest(kind=kind, failure=failure):
                    result = self.run_cleanup(**({"query_failure": kind} if failure else {"residue": kind}))
                    self.assertEqual(70, result.returncode)
                    self.assertNotIn("timeout 복구", result.stdout)

    def test_regular_removal_failure_is_not_timeout_recovery(self):
        """일반 삭제 실패는 사후 빈 조회만으로 성공 처리하지 않는다."""
        self.assertEqual(70, self.run_cleanup(removal_status=1).returncode)

    def test_original_failure_is_preserved_even_after_recovery(self):
        """정리 복구 성공 여부와 무관하게 본 작업의 실패 코드를 보존한다."""
        self.assertEqual(23, self.run_cleanup(original_status=23).returncode)
        self.assertEqual(23, self.run_cleanup(original_status=23, residue="image").returncode)
