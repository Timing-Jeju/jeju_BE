#!/usr/bin/env python3
"""운영 적용 가능 SQL의 Supabase Auth 소유권 침해를 검사한다."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LOCAL_ONLY_SQL_ROOTS = (Path("db/local-postgres"),)


@dataclass(frozen=True)
class Violation:
    path: Path
    line: int
    rule: str


FORBIDDEN_PATTERNS = (
    (
        "Supabase 소유 auth 스키마 DDL",
        re.compile(
            r"\b(?:create|alter|drop)\s+schema\s+"
            r"(?:(?:if\s+(?:not\s+)?exists)\s+)?auth\b",
            re.IGNORECASE,
        ),
    ),
    (
        "Supabase Auth 소유 객체 DDL",
        re.compile(
            r"\b(?:create(?:\s+or\s+replace)?|alter|drop)\s+"
            r"(?:table|function|procedure|routine|view)\s+"
            r"(?:(?:if\s+(?:not\s+)?exists)\s+)?"
            r"auth\s*\.\s*(?:users|uid)\b",
            re.IGNORECASE,
        ),
    ),
    (
        "auth.users 직접 INSERT",
        re.compile(
            r"\binsert\s+into\s+(?:only\s+)?auth\s*\.\s*users\b",
            re.IGNORECASE,
        ),
    ),
)


def _strip_comments(sql: str) -> str:
    without_blocks = re.sub(r"/\*.*?\*/", lambda match: "\n" * match.group().count("\n"), sql, flags=re.DOTALL)
    return re.sub(r"--[^\n]*", "", without_blocks)


def _normalize_identifiers(sql: str) -> str:
    return re.sub(r'"(auth|users|uid)"', r"\1", sql, flags=re.IGNORECASE)


def _is_local_only(path: Path, root: Path) -> bool:
    relative = path.relative_to(root)
    return any(relative.is_relative_to(local_root) for local_root in LOCAL_ONLY_SQL_ROOTS)


def deploy_sql_files(root: Path = ROOT) -> tuple[Path, ...]:
    return tuple(
        path
        for path in sorted(root.rglob("*.sql"))
        if not _is_local_only(path, root)
    )


def find_violations(root: Path = ROOT) -> tuple[Violation, ...]:
    violations: list[Violation] = []
    for path in deploy_sql_files(root):
        sql = _normalize_identifiers(_strip_comments(path.read_text(encoding="utf-8")))
        for rule, pattern in FORBIDDEN_PATTERNS:
            for match in pattern.finditer(sql):
                line = sql.count("\n", 0, match.start()) + 1
                violations.append(Violation(path.relative_to(root), line, rule))
    return tuple(violations)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="운영 적용 가능 SQL의 Supabase Auth 소유권 침해를 검사합니다."
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="검사할 저장소 루트")
    args = parser.parse_args()

    violations = find_violations(args.root.resolve())
    if violations:
        print("배포 SQL 정책 위반을 발견했습니다.", file=sys.stderr)
        for violation in violations:
            print(
                f"- {violation.path}:{violation.line}: {violation.rule}",
                file=sys.stderr,
            )
        return 1

    print("배포 SQL 정책 검사 성공")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
