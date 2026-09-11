#!/usr/bin/env python3
"""검토된 위치 cutover SQL을 한 PostgreSQL transaction으로만 적용한다."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import stat
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Callable, Sequence
from urllib.parse import urlsplit

from location_cutover_group import render_supabase


ROOT = Path(__file__).resolve().parents[1]
GROUP_SQL = Path("db/local-postgres/location_cutover_supabase.sql")
LEDGER_QUERY = """
select current_setting('server_version_num')::integer / 10000;
select string_agg(column_name || ':' || data_type || ':' || is_nullable,
                  ',' order by ordinal_position)
from information_schema.columns
where table_schema='supabase_migrations' and table_name='schema_migrations';
select count(*)
from pg_constraint
where conrelid='supabase_migrations.schema_migrations'::regclass
  and contype='p' and conkey=array[
    (select attnum from pg_attribute
     where attrelid='supabase_migrations.schema_migrations'::regclass
       and attname='version')];
"""
POSTCHECK_QUERY = """
select timing_jeju_planner_private.user_location_guard_purge_revision();
select coalesce(sum(residue_count), -1)
from timing_jeju_planner_private.user_location_residue_counts();
select count(*)
from supabase_migrations.schema_migrations
where version in ('20260918000017','20260918000018');
"""
EXPECTED_LEDGER_COLUMNS = "version:text:NO,statements:ARRAY:YES,name:text:YES"


class CutoverRejected(RuntimeError):
    """쓰기 전에 또는 검증 실패 뒤 cutover를 고정 메시지로 중단한다."""


Runner = Callable[..., subprocess.CompletedProcess[str]]


def _run(
    runner: Runner,
    argv: Sequence[str],
    *,
    stage: str,
    cwd: Path,
    env: dict[str, str] | None = None,
    input_bytes: bytes | None = None,
    timeout: int = 120,
) -> str:
    try:
        result = runner(
            list(argv),
            cwd=cwd,
            env=env,
            input=input_bytes,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise CutoverRejected(f"{stage} 실행에 실패했습니다.") from exc
    if result.returncode != 0:
        raise CutoverRejected(f"{stage} 검증에 실패했습니다.")
    stdout = result.stdout
    if isinstance(stdout, bytes):
        try:
            stdout = stdout.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise CutoverRejected(f"{stage} 출력이 UTF-8이 아닙니다.") from exc
    return stdout.strip()


def _read_open_regular_file(
    path: Path, *, stage: str, exact_mode: int | None = None, executable: bool = False
) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise CutoverRejected(f"{stage} 파일을 열 수 없습니다.") from exc
    try:
        metadata = os.fstat(descriptor)
        mode = stat.S_IMODE(metadata.st_mode)
        if not stat.S_ISREG(metadata.st_mode):
            raise CutoverRejected(f"{stage} 파일은 일반 파일이어야 합니다.")
        if metadata.st_uid not in {0, os.getuid()}:
            raise CutoverRejected(f"{stage} 파일 소유자가 허용되지 않습니다.")
        if exact_mode is not None and mode != exact_mode:
            raise CutoverRejected(f"{stage} 파일 권한은 {exact_mode:04o}이어야 합니다.")
        if exact_mode is None and mode & (stat.S_IWGRP | stat.S_IWOTH):
            raise CutoverRejected(f"{stage} 파일은 group/world 쓰기를 허용할 수 없습니다.")
        if executable and not mode & (stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH):
            raise CutoverRejected(f"{stage} 파일은 실행 가능해야 합니다.")
        chunks: list[bytes] = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
        return b"".join(chunks)
    except OSError as exc:
        raise CutoverRejected(f"{stage} 파일을 읽을 수 없습니다.") from exc
    finally:
        os.close(descriptor)


def _read_database_url(path: Path) -> str:
    payload = _read_open_regular_file(path, stage="DB URL", exact_mode=0o600)
    if not payload or b"\n" in payload or b"\r" in payload or b"\x00" in payload:
        raise CutoverRejected("DB URL 파일은 개행 없는 단일 값이어야 합니다.")
    try:
        value = payload.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise CutoverRejected("DB URL 파일은 UTF-8이어야 합니다.") from exc
    try:
        parsed = urlsplit(value)
    except ValueError as exc:
        raise CutoverRejected("DB URL은 PostgreSQL 연결 문자열이어야 합니다.") from exc
    if parsed.scheme not in {"postgres", "postgresql"} or not parsed.hostname:
        raise CutoverRejected("DB URL은 PostgreSQL 연결 문자열이어야 합니다.")
    return value


def _load_psql_binary(path: Path, expected_sha256: str) -> bytes:
    if not path.is_absolute():
        raise CutoverRejected("psql 경로는 절대 경로여야 합니다.")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise CutoverRejected("psql SHA-256은 소문자 64자리여야 합니다.")
    try:
        resolved = path.resolve(strict=True)
    except OSError as exc:
        raise CutoverRejected("psql 파일을 찾을 수 없습니다.") from exc
    payload = _read_open_regular_file(resolved, stage="psql", executable=True)
    if hashlib.sha256(payload).hexdigest() != expected_sha256:
        raise CutoverRejected("psql binary SHA-256이 검토값과 다릅니다.")
    return payload


def _write_pinned_psql(directory: Path, payload: bytes) -> Path:
    target = directory / "psql"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0)
    try:
        descriptor = os.open(target, flags, 0o500)
        try:
            remaining = memoryview(payload)
            while remaining:
                written = os.write(descriptor, remaining)
                if written == 0:
                    raise OSError("psql copy made no progress")
                remaining = remaining[written:]
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        os.chmod(target, 0o500)
    except OSError as exc:
        raise CutoverRejected("검증한 psql 실행 복사본을 만들 수 없습니다.") from exc
    return target


def apply_cutover(
    *,
    reviewed_sha: str,
    db_url_file: Path,
    psql_bin: Path,
    psql_sha256: str,
    expected_psql_major: int,
    root: Path = ROOT,
    runner: Runner = subprocess.run,
) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", reviewed_sha):
        raise CutoverRejected("reviewed SHA는 소문자 40자리 Git SHA여야 합니다.")
    head = _run(runner, ["git", "rev-parse", "HEAD"], stage="Git HEAD", cwd=root)
    if head != reviewed_sha:
        raise CutoverRejected("현재 HEAD가 reviewed SHA와 다릅니다.")
    if _run(
        runner,
        ["git", "status", "--porcelain", "--untracked-files=all"],
        stage="Git 상태",
        cwd=root,
    ):
        raise CutoverRejected("작업 트리가 깨끗하지 않습니다.")
    try:
        expected_sql = render_supabase(root)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise CutoverRejected("cutover SQL 원본 검증에 실패했습니다.") from exc
    artifact_sql = _read_open_regular_file(root / GROUP_SQL, stage="cutover SQL")
    if artifact_sql != expected_sql:
        raise CutoverRejected("cutover SQL byte가 검토 원본과 다릅니다.")
    psql_payload = _load_psql_binary(psql_bin, psql_sha256)
    if _run(
        runner,
        ["git", "status", "--porcelain", "--untracked-files=all"],
        stage="최종 Git 상태",
        cwd=root,
    ):
        raise CutoverRejected("검증 도중 작업 트리가 변경되었습니다.")

    database_url = _read_database_url(db_url_file)
    child_env = {
        "PGDATABASE": database_url,
        "PGCONNECT_TIMEOUT": "10",
    }
    with tempfile.TemporaryDirectory(prefix="location-cutover-psql-") as directory:
        pinned_psql = _write_pinned_psql(Path(directory), psql_payload)
        psql_version = _run(
            runner,
            [str(pinned_psql), "--version"],
            stage="psql",
            cwd=root,
            env={},
        )
        match = re.search(r"PostgreSQL\)\s+(\d+)(?:\.|\s|$)", psql_version)
        if match is None or int(match.group(1)) != expected_psql_major:
            raise CutoverRejected("검토한 psql major 버전과 다릅니다.")
        base = [
            str(pinned_psql),
            "-X",
            "--no-psqlrc",
            "--no-password",
            "--set=ON_ERROR_STOP=1",
            "--tuples-only",
            "--no-align",
        ]
        ledger = _run(
            runner,
            [*base, "--command", LEDGER_QUERY],
            stage="cutover 선행 ledger",
            cwd=root,
            env=child_env,
        ).splitlines()
        if ledger != [str(expected_psql_major), EXPECTED_LEDGER_COLUMNS, "1"]:
            raise CutoverRejected("cutover 선행 ledger 구조가 검토값과 다릅니다.")
        _run(
            runner,
            [*base, "--file=-"],
            stage="원자 위치 cutover",
            cwd=root,
            env=child_env,
            input_bytes=artifact_sql,
            timeout=900,
        )
        postcheck = _run(
            runner,
            [*base, "--command", POSTCHECK_QUERY],
            stage="cutover 사후 상태",
            cwd=root,
            env=child_env,
        ).splitlines()
        if postcheck != ["20260918000018", "0", "2"]:
            raise CutoverRejected("cutover 사후 marker/residue/ledger가 검토값과 다릅니다.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reviewed-sha", required=True)
    parser.add_argument("--db-url-file", required=True, type=Path)
    parser.add_argument("--psql-bin", required=True, type=Path)
    parser.add_argument("--psql-sha256", required=True)
    parser.add_argument("--expected-psql-major", required=True, type=int, choices=(16, 17))
    args = parser.parse_args()
    try:
        apply_cutover(
            reviewed_sha=args.reviewed_sha,
            db_url_file=args.db_url_file,
            psql_bin=args.psql_bin,
            psql_sha256=args.psql_sha256,
            expected_psql_major=args.expected_psql_major,
        )
    except CutoverRejected as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print("위치 cutover 원자 적용과 사후 검증이 완료되었습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
