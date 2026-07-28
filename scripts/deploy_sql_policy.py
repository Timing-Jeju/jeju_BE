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

_DOLLAR_QUOTE_START = re.compile(r"\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$")


def _is_escape_string_start(sql: str, quote_index: int) -> bool:
    if quote_index == 0 or sql[quote_index - 1] not in ("e", "E"):
        return False
    prefix_index = quote_index - 2
    return prefix_index < 0 or not (
        sql[prefix_index].isalnum() or sql[prefix_index] in ("_", "$")
    )


def _mask_comments_preserving_literals(sql: str) -> str:
    """실제 주석만 가리고 동적 SQL까지 검사하도록 모든 literal 본문을 보존한다."""
    result: list[str] = []
    index = 0
    state = "normal"
    block_depth = 0
    dollar_delimiter = ""
    escape_backslash = False

    while index < len(sql):
        character = sql[index]

        if state == "line_comment":
            if character == "\n":
                result.append(character)
                state = "normal"
            else:
                result.append(" ")
            index += 1
            continue

        if state == "block_comment":
            if sql.startswith("/*", index):
                result.extend((" ", " "))
                block_depth += 1
                index += 2
            elif sql.startswith("*/", index):
                result.extend((" ", " "))
                block_depth -= 1
                index += 2
                if block_depth == 0:
                    state = "normal"
            else:
                result.append("\n" if character == "\n" else " ")
                index += 1
            continue

        if state == "single_quote":
            result.append(character)
            if escape_backslash and character == "\\" and index + 1 < len(sql):
                result.append(sql[index + 1])
                index += 2
            elif character == "'" and index + 1 < len(sql) and sql[index + 1] == "'":
                result.append(sql[index + 1])
                index += 2
            else:
                if character == "'":
                    state = "normal"
                index += 1
            continue

        if state == "quoted_identifier":
            result.append(character)
            if character == '"' and index + 1 < len(sql) and sql[index + 1] == '"':
                result.append(sql[index + 1])
                index += 2
            else:
                if character == '"':
                    state = "normal"
                index += 1
            continue

        if state == "dollar_quote":
            if sql.startswith(dollar_delimiter, index):
                result.extend(dollar_delimiter)
                index += len(dollar_delimiter)
                state = "normal"
            else:
                result.append(character)
                index += 1
            continue

        if sql.startswith("--", index):
            result.extend((" ", " "))
            index += 2
            state = "line_comment"
        elif sql.startswith("/*", index):
            result.extend((" ", " "))
            index += 2
            state = "block_comment"
            block_depth = 1
        elif character == "'":
            result.append(character)
            escape_backslash = _is_escape_string_start(sql, index)
            index += 1
            state = "single_quote"
        elif character == '"':
            result.append(character)
            index += 1
            state = "quoted_identifier"
        elif character == "$":
            delimiter_match = _DOLLAR_QUOTE_START.match(sql, index)
            if delimiter_match is None:
                result.append(character)
                index += 1
            else:
                dollar_delimiter = delimiter_match.group()
                result.extend(dollar_delimiter)
                index += len(dollar_delimiter)
                state = "dollar_quote"
        else:
            result.append(character)
            index += 1

    return "".join(result)


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
        sql = _normalize_identifiers(
            _mask_comments_preserving_literals(path.read_text(encoding="utf-8"))
        )
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
