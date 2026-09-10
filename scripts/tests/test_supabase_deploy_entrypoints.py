"""Supabase 배포가 원자 cutover를 우회하는 직접 push를 포함하지 않는지 검증한다."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from validate_supabase_deploy_entrypoints import find_violations  # noqa: E402


class SupabaseDeployEntrypointsTest(unittest.TestCase):
    def test_repository_executable_paths_do_not_call_raw_supabase_db_push(self) -> None:
        """현재 실행 스크립트와 workflow가 원자 cutover를 우회하지 않는지 검사한다."""
        self.assertEqual((), find_violations(ROOT))

    def test_shell_and_workflow_raw_push_commands_are_rejected(self) -> None:
        """고정 CLI와 환경변수 CLI를 통한 직접 db push를 모두 거부한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write(root, "scripts/release.sh", "supabase db push --linked\n")
            self._write(
                root,
                ".github/workflows/deploy.yml",
                'run: "$SUPABASE_BIN" db push --db-url "$DATABASE_URL"\n',
            )
            self._write(
                root,
                "scripts/release.py",
                'subprocess.run(["supabase", "db", "push"], check=True)\n',
            )

            violations = find_violations(root)

        self.assertEqual(
            (
                ".github/workflows/deploy.yml:1",
                "scripts/release.py:1",
                "scripts/release.sh:1",
            ),
            tuple(f"{item.path.as_posix()}:{item.line}" for item in violations),
        )

    def test_comments_and_non_push_commands_are_allowed(self) -> None:
        """설명 주석과 로컬 reset 같은 비배포 명령은 정책 위반으로 오인하지 않는다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write(
                root,
                "scripts/local-smoke.sh",
                "# supabase db push is forbidden here\nsupabase db reset\n",
            )
            self._write(
                root,
                ".github/workflows/check.yml",
                "# run: supabase db push\nrun: supabase migration list\n",
            )

            self.assertEqual((), find_violations(root))

    def test_cutover_wrapper_cannot_delegate_to_raw_db_push(self) -> None:
        """cutover 래퍼도 두 migration을 순차 적용하는 db push에 위임하지 못한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write(
                root,
                "scripts/apply-location-cutover.sh",
                "supabase db push --dry-run\n",
            )

            violations = find_violations(root)

        self.assertEqual(1, len(violations))
        self.assertEqual(
            "scripts/apply-location-cutover.sh", violations[0].path.as_posix()
        )

    def test_multiline_shell_push_is_rejected_at_command_start(self) -> None:
        """줄 연속 표기로 분리한 직접 db push도 첫 명령 줄에서 거부한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write(
                root,
                "scripts/release.sh",
                '"$SUPABASE_BIN" \\\n  db push --linked\n',
            )

            violations = find_violations(root)

        self.assertEqual(1, len(violations))
        self.assertEqual(1, violations[0].line)

    def test_package_runner_push_commands_are_rejected(self) -> None:
        """고정 버전 package runner로 실행한 직접 db push도 거부한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write(
                root,
                "scripts/release.sh",
                "npx --yes supabase@2.110.0 db push --linked\n",
            )
            self._write(
                root,
                "scripts/release.py",
                'subprocess.run(["npx", "supabase@2.110.0", "db", "push"], check=True)\n',
            )

            violations = find_violations(root)

        self.assertEqual(
            ("scripts/release.py:1", "scripts/release.sh:1"),
            tuple(f"{item.path.as_posix()}:{item.line}" for item in violations),
        )

    def test_multiline_python_argv_push_is_rejected(self) -> None:
        """괄호 안 여러 줄 Python argv로 나눈 직접 db push도 호출 시작 줄에서 거부한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write(
                root,
                "scripts/release.py",
                "import subprocess\nsubprocess.run([\n"
                '    "supabase",\n    "db",\n    "push",\n], check=True)\n',
            )

            violations = find_violations(root)

        self.assertEqual(("scripts/release.py:2",), self._labels(violations))

    def test_arbitrary_shell_runner_variable_push_is_rejected(self) -> None:
        """이름에 supabase가 없는 shell 실행 변수로 호출한 db push도 거부한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write(
                root,
                "scripts/release.sh",
                'CLI="supabase"\n"$CLI" db push --linked\n',
            )

            violations = find_violations(root)

        self.assertEqual(("scripts/release.sh:2",), self._labels(violations))

    def test_makefile_extensionless_script_and_package_script_are_rejected(self) -> None:
        """Makefile·확장자 없는 실행 파일·package script의 직접 db push를 모두 거부한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write(root, "Makefile", "deploy:\n\tsupabase db push --linked\n")
            self._write(
                root,
                "package.json",
                '{\n  "scripts": {\n    "deploy": "supabase db push --linked"\n  }\n}\n',
            )
            self._write(
                root,
                "scripts/release",
                "#!/bin/sh\nsupabase db push --linked\n",
            )
            (root / "scripts/release").chmod(0o755)

            violations = find_violations(root)

        self.assertEqual(
            ("Makefile:2", "package.json:3", "scripts/release:2"),
            self._labels(violations),
        )

    def test_github_composite_action_push_is_rejected(self) -> None:
        """재사용 GitHub action의 shell 단계도 workflow와 같은 배포 정책으로 검사한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write(
                root,
                ".github/actions/deploy/action.yml",
                "runs:\n  using: composite\n  steps:\n"
                '    - run: "$CLI" db push --linked\n      shell: bash\n',
            )

            violations = find_violations(root)

        self.assertEqual(
            (".github/actions/deploy/action.yml:4",), self._labels(violations)
        )

    @staticmethod
    def _labels(violations) -> tuple[str, ...]:
        return tuple(f"{item.path.as_posix()}:{item.line}" for item in violations)

    @staticmethod
    def _write(root: Path, relative: str, content: str) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
