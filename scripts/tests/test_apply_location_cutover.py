"""위치 cutover 실행기가 쓰기 전에 모든 배포 전제를 검증하는지 확인한다."""

from __future__ import annotations

import hashlib
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from apply_location_cutover import CutoverRejected, apply_cutover  # noqa: E402


SHA = "a" * 40
DATABASE_URL = "postgresql://localhost/cutover_fixture"


class FakeRunner:
    def __init__(self, outputs: list[str]) -> None:
        self.outputs = list(outputs)
        self.calls: list[tuple[list[str], dict]] = []

    def __call__(self, argv, **kwargs):
        self.calls.append((list(argv), kwargs))
        return subprocess.CompletedProcess(
            argv, 0, stdout=self.outputs.pop(0) + "\n", stderr=""
        )


class ApplyLocationCutoverTest(unittest.TestCase):
    def test_reviewed_sha가_아니면_외부_명령_전에_거부한다(self) -> None:
        """형식이 잘못된 reviewed SHA는 Git이나 DB를 실행하기 전에 거부한다."""
        runner = FakeRunner([])
        with self.assertRaises(CutoverRejected):
            apply_cutover(
                reviewed_sha="main",
                db_url_file=Path("missing"),
                psql_bin=Path("/missing/psql"),
                psql_sha256="0" * 64,
                expected_psql_major=17,
                runner=runner,
            )
        self.assertEqual([], runner.calls)

    def test_head와_clean_tree가_쓰기보다_먼저_검증된다(self) -> None:
        """HEAD나 작업 트리 drift가 있으면 psql과 DB를 호출하지 않는다."""
        for outputs in (["b" * 40], [SHA, " M tracked.sql"]):
            runner = FakeRunner(outputs)
            with self.subTest(outputs=outputs), self.assertRaises(CutoverRejected):
                apply_cutover(
                    reviewed_sha=SHA,
                    db_url_file=Path("missing"),
                    psql_bin=Path("/missing/psql"),
                    psql_sha256="0" * 64,
                    expected_psql_major=17,
                    runner=runner,
                )
            self.assertFalse(any("--file=-" in call[0] for call in runner.calls))

    def test_psql은_절대경로와_검토된_sha256을_요구한다(self) -> None:
        """psql은 절대경로의 일반 실행 파일이며 바이트 SHA-256이 일치해야 한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = self._root(Path(directory))
            credential_file = self._secret(root)
            psql, digest = self._psql(root)
            for binary, expected in ((Path("psql"), digest), (psql, "0" * 64)):
                runner = FakeRunner([SHA, ""])
                with self.subTest(binary=binary, expected=expected), patch(
                    "apply_location_cutover.render_supabase",
                    return_value=self._artifact(root),
                ), self.assertRaises(CutoverRejected):
                    apply_cutover(
                        reviewed_sha=SHA,
                        db_url_file=credential_file,
                        psql_bin=binary,
                        psql_sha256=expected,
                        expected_psql_major=17,
                        root=root,
                        runner=runner,
                    )
                self.assertFalse(any("--file=-" in call[0] for call in runner.calls))

    def test_db_url_file은_현재_소유자_0600_일반파일만_허용한다(self) -> None:
        """권한이 넓거나 symlink인 DB URL 파일은 내용을 사용하지 않고 거부한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = self._root(Path(directory))
            target = root / "database-url"
            target.write_text(DATABASE_URL)
            target.chmod(0o644)
            link = root / "database-url-link"
            link.symlink_to(target)
            for path in (target, link):
                runner = self._pre_db_runner()
                with self.subTest(path=path), self.assertRaises(CutoverRejected):
                    self._apply(root, path, runner)
                self.assertFalse(any("--file=-" in call[0] for call in runner.calls))

    def test_sql_artifact가_검토_원본과_다르면_db_전에_거부한다(self) -> None:
        """생성 SQL 경로의 바이트가 원본 재생성값과 다르면 DB에 연결하지 않는다."""
        with tempfile.TemporaryDirectory() as directory:
            root = self._root(Path(directory))
            runner = FakeRunner([SHA, ""])
            with patch(
                "apply_location_cutover.render_supabase", return_value=b"different\n"
            ), self.assertRaises(CutoverRejected):
                self._apply(root, self._secret(root), runner, patch_render=False)
            self.assertEqual(2, len(runner.calls))

    def test_psql_major와_ledger_mismatch는_group_sql_전에_거부한다(self) -> None:
        """psql·서버 major와 ledger 구조가 검토값과 다르면 원자 SQL을 실행하지 않는다."""
        with tempfile.TemporaryDirectory() as directory:
            root = self._root(Path(directory))
            credential_file = self._secret(root)
            for extra in (
                ["psql (PostgreSQL) 16.9"],
                ["psql (PostgreSQL) 17.6", "17\nversion:text:NO\n0"],
            ):
                runner = self._pre_db_runner(extra)
                with self.subTest(extra=extra), self.assertRaises(CutoverRejected):
                    self._apply(root, credential_file, runner)
                self.assertFalse(any("--file=-" in call[0] for call in runner.calls))

    def test_정상_적용은_검증한_sql_bytes를_stdin으로_한번만_실행한다(self) -> None:
        """정상 경로는 URL을 숨기고 고정 SQL 바이트를 표준입력으로 한 번 실행한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = self._root(Path(directory))
            credential_file = self._secret(root)
            artifact = self._artifact(root)
            runner = self._pre_db_runner(
                [
                    "psql (PostgreSQL) 17.6",
                    "17\nversion:text:NO,statements:ARRAY:YES,name:text:YES\n1",
                    "BEGIN\nCOMMIT",
                    "20260918000018\n0\n2",
                ]
            )
            with patch.dict(
                os.environ,
                {
                    "PGPASSWORD": "inherited-credential_file",
                    "LD_PRELOAD": "/untrusted/inject.so",
                    "DYLD_INSERT_LIBRARIES": "/untrusted/inject.dylib",
                },
            ):
                self._apply(root, credential_file, runner)
            file_calls = [call for call in runner.calls if "--file=-" in call[0]]
            self.assertEqual(1, len(file_calls))
            self.assertEqual(artifact, file_calls[0][1]["input"])
            for argv, kwargs in runner.calls:
                self.assertNotIn("postgresql://", " ".join(argv))
                env = kwargs.get("env")
                if env is not None:
                    self.assertNotIn("PGPASSWORD", env)
                    self.assertNotIn("LD_PRELOAD", env)
                    self.assertNotIn("DYLD_INSERT_LIBRARIES", env)
                    if "--version" not in argv:
                        self.assertEqual(
                            {"PGDATABASE": DATABASE_URL, "PGCONNECT_TIMEOUT": "10"},
                            env,
                        )

    def test_검증한_psql_복사본만_모든_db_호출에_사용한다(self) -> None:
        """버전·선행·적용·사후 조회는 같은 비공개 psql 복사본으로 실행한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = self._root(Path(directory))
            runner = self._pre_db_runner(
                [
                    "psql (PostgreSQL) 17.6",
                    "17\nversion:text:NO,statements:ARRAY:YES,name:text:YES\n1",
                    "COMMIT",
                    "20260918000018\n0\n2",
                ]
            )
            self._apply(root, self._secret(root), runner)
            psql_calls = runner.calls[3:]
            self.assertEqual(4, len(psql_calls))
            self.assertEqual(1, len({call[0][0] for call in psql_calls}))
            self.assertIn("location-cutover-psql-", psql_calls[0][0][0])

    def test_사후검증_mismatch는_성공으로_보고하지_않는다(self) -> None:
        """적용 명령이 끝나도 marker·residue·ledger가 다르면 실패로 종료한다."""
        with tempfile.TemporaryDirectory() as directory:
            root = self._root(Path(directory))
            runner = self._pre_db_runner(
                [
                    "psql (PostgreSQL) 17.6",
                    "17\nversion:text:NO,statements:ARRAY:YES,name:text:YES\n1",
                    "COMMIT",
                    "20260918000018\n1\n2",
                ]
            )
            with self.assertRaises(CutoverRejected):
                self._apply(root, self._secret(root), runner)

    def _pre_db_runner(self, extra: list[str] | None = None) -> FakeRunner:
        return FakeRunner([SHA, "", ""] + (extra or []))

    def _root(self, root: Path) -> Path:
        (root / "db/local-postgres").mkdir(parents=True)
        (root / "db/local-postgres/location_cutover_supabase.sql").write_bytes(
            b"begin; commit;\n"
        )
        return root

    def _artifact(self, root: Path) -> bytes:
        return (root / "db/local-postgres/location_cutover_supabase.sql").read_bytes()

    def _secret(self, root: Path) -> Path:
        path = root / "database-url"
        path.write_text(DATABASE_URL)
        path.chmod(stat.S_IRUSR | stat.S_IWUSR)
        self.assertEqual(os.getuid(), path.stat().st_uid)
        return path

    def _psql(self, root: Path) -> tuple[Path, str]:
        path = root / "psql17"
        if not path.exists():
            path.write_bytes(b"reviewed fake psql binary\n")
            path.chmod(0o500)
        return path, hashlib.sha256(path.read_bytes()).hexdigest()

    def _apply(
        self,
        root: Path,
        credential_file: Path,
        runner: FakeRunner,
        *,
        patch_render: bool = True,
    ) -> None:
        psql, digest = self._psql(root)
        render_patch = patch(
            "apply_location_cutover.render_supabase", return_value=self._artifact(root)
        )
        if patch_render:
            render_patch.start()
        try:
            apply_cutover(
                reviewed_sha=SHA,
                db_url_file=credential_file,
                psql_bin=psql,
                psql_sha256=digest,
                expected_psql_major=17,
                root=root,
                runner=runner,
            )
        finally:
            if patch_render:
                render_patch.stop()


if __name__ == "__main__":
    unittest.main()
