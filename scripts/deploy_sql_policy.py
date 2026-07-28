#!/usr/bin/env python3
"""운영 적용 가능 SQL의 Supabase Auth 소유권 침해를 검사한다."""

from __future__ import annotations

import argparse
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


@dataclass(frozen=True)
class SqlToken:
    value: str
    start: int
    quoted_identifier: bool = False


AUTH_SCHEMA_DDL_RULE = "Supabase 소유 auth 스키마 DDL"
AUTH_OBJECT_DDL_RULE = "Supabase Auth 소유 객체 DDL"
AUTH_USERS_INSERT_RULE = "auth.users 직접 INSERT"

_DDL_OBJECT_TYPES = (
    (("foreign", "table"), "users"),
    (("materialized", "view"), "users"),
    (("table",), "users"),
    (("view",), "users"),
    (("function",), "uid"),
    (("procedure",), "uid"),
    (("routine",), "uid"),
)


def _is_escape_string_start(sql: str, quote_index: int) -> bool:
    if quote_index == 0 or sql[quote_index - 1] not in ("e", "E"):
        return False
    prefix_index = quote_index - 2
    return prefix_index < 0 or not (
        sql[prefix_index].isalnum() or sql[prefix_index] in ("_", "$")
    )


def _is_identifier_start(character: str) -> bool:
    """PostgreSQL 식별자 시작 문자(underscore, letter, non-ASCII)를 판별한다."""
    return character == "_" or character.isalpha() or ord(character) >= 128


def _is_identifier_continuation(character: str) -> bool:
    return _is_identifier_start(character) or character.isdigit() or character == "$"


def _dollar_quote_delimiter_at(sql: str, index: int) -> str | None:
    """토큰 경계에서 시작하는 PostgreSQL dollar-quote delimiter를 반환한다."""
    if sql[index] != "$":
        return None
    if index > 0 and _is_identifier_continuation(sql[index - 1]):
        return None
    if index + 1 < len(sql) and sql[index + 1] == "$":
        return "$$"
    if index + 1 >= len(sql) or not _is_identifier_start(sql[index + 1]):
        return None

    end = index + 2
    while end < len(sql) and sql[end] != "$":
        if not _is_identifier_continuation(sql[end]):
            return None
        end += 1
    if end >= len(sql):
        return None
    return sql[index : end + 1]


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
            delimiter = _dollar_quote_delimiter_at(sql, index)
            if delimiter is None:
                result.append(character)
                index += 1
            else:
                dollar_delimiter = delimiter
                result.extend(dollar_delimiter)
                index += len(dollar_delimiter)
                state = "dollar_quote"
        else:
            result.append(character)
            index += 1

    return "".join(result)


def _sql_tokens(sql: str) -> tuple[SqlToken, ...]:
    """정책 검사에 필요한 식별자와 구두점만 원문 위치와 함께 토큰화한다."""
    tokens: list[SqlToken] = []
    index = 0
    while index < len(sql):
        character = sql[index]
        if character.isspace():
            index += 1
            continue
        if character == '"':
            start = index
            index += 1
            value: list[str] = []
            while index < len(sql):
                if sql[index] == '"':
                    if index + 1 < len(sql) and sql[index + 1] == '"':
                        value.append('"')
                        index += 2
                        continue
                    index += 1
                    break
                value.append(sql[index])
                index += 1
            tokens.append(SqlToken("".join(value), start, quoted_identifier=True))
            continue
        if _is_identifier_start(character) or character.isdigit():
            start = index
            index += 1
            while index < len(sql) and _is_identifier_continuation(sql[index]):
                index += 1
            tokens.append(SqlToken(sql[start:index], start))
            continue
        tokens.append(SqlToken(character, index))
        index += 1
    return tuple(tokens)


def _is_keyword(token: SqlToken, keyword: str) -> bool:
    return not token.quoted_identifier and token.value.casefold() == keyword


def _identifier_is(token: SqlToken, identifier: str) -> bool:
    if token.quoted_identifier:
        return token.value == identifier
    return token.value.casefold() == identifier


def _matches_keywords(
    tokens: tuple[SqlToken, ...], index: int, keywords: tuple[str, ...]
) -> bool:
    return index + len(keywords) <= len(tokens) and all(
        _is_keyword(tokens[index + offset], keyword)
        for offset, keyword in enumerate(keywords)
    )


def _consume_create_modifiers(tokens: tuple[SqlToken, ...], index: int) -> int:
    if _matches_keywords(tokens, index, ("or", "replace")):
        index += 2

    has_temp_scope = index + 1 < len(tokens) and any(
        _is_keyword(tokens[index], scope) for scope in ("global", "local")
    )
    if has_temp_scope and (
        _is_keyword(tokens[index + 1], "temp")
        or _is_keyword(tokens[index + 1], "temporary")
    ):
        index += 2
    elif index < len(tokens) and any(
        _is_keyword(tokens[index], modifier)
        for modifier in ("temp", "temporary", "unlogged")
    ):
        index += 1

    if index < len(tokens) and _is_keyword(tokens[index], "recursive"):
        index += 1
    return index


def _consume_if_exists(tokens: tuple[SqlToken, ...], index: int) -> int:
    if not _matches_keywords(tokens, index, ("if",)):
        return index
    index += 1
    if index < len(tokens) and _is_keyword(tokens[index], "not"):
        index += 1
    if index < len(tokens) and _is_keyword(tokens[index], "exists"):
        return index + 1
    return index - 1


def _qualified_owned_name(
    tokens: tuple[SqlToken, ...], index: int, object_name: str
) -> bool:
    return (
        index + 2 < len(tokens)
        and _identifier_is(tokens[index], "auth")
        and tokens[index + 1].value == "."
        and _identifier_is(tokens[index + 2], object_name)
    )


def _drop_target_starts(
    tokens: tuple[SqlToken, ...], index: int
) -> tuple[int, ...]:
    """DROP 목록에서 함수 인자 괄호 밖의 각 target 시작 위치를 반환한다."""
    starts = [index]
    target_start = index
    parenthesis_depth = 0
    cursor = index
    while cursor < len(tokens):
        token = tokens[cursor]
        if token.value == "(":
            parenthesis_depth += 1
        elif token.value == ")" and parenthesis_depth > 0:
            parenthesis_depth -= 1
        elif parenthesis_depth == 0:
            if token.value == ";":
                break
            is_drop_behavior = any(
                _is_keyword(token, behavior) for behavior in ("cascade", "restrict")
            )
            if (
                is_drop_behavior
                and cursor > target_start
                and tokens[cursor - 1].value != "."
            ):
                break
            if token.value == "," and cursor + 1 < len(tokens):
                target_start = cursor + 1
                starts.append(target_start)
        cursor += 1
    return tuple(starts)


def _match_ddl(tokens: tuple[SqlToken, ...], index: int) -> str | None:
    verb = tokens[index].value.casefold()
    if tokens[index].quoted_identifier or verb not in {"create", "alter", "drop"}:
        return None

    cursor = index + 1
    if verb == "create":
        cursor = _consume_create_modifiers(tokens, cursor)

    if _matches_keywords(tokens, cursor, ("schema",)):
        cursor = _consume_if_exists(tokens, cursor + 1)
        target_starts = (
            _drop_target_starts(tokens, cursor) if verb == "drop" else (cursor,)
        )
        if any(
            target < len(tokens) and _identifier_is(tokens[target], "auth")
            for target in target_starts
        ):
            return AUTH_SCHEMA_DDL_RULE
        return None

    for object_type, protected_name in _DDL_OBJECT_TYPES:
        if not _matches_keywords(tokens, cursor, object_type):
            continue
        cursor = _consume_if_exists(tokens, cursor + len(object_type))
        if cursor < len(tokens) and _is_keyword(tokens[cursor], "only"):
            cursor += 1
        target_starts = (
            _drop_target_starts(tokens, cursor) if verb == "drop" else (cursor,)
        )
        if any(
            _qualified_owned_name(tokens, target, protected_name)
            for target in target_starts
        ):
            return AUTH_OBJECT_DDL_RULE
        return None
    return None


def _match_auth_users_insert(tokens: tuple[SqlToken, ...], index: int) -> bool:
    if not _matches_keywords(tokens, index, ("insert", "into")):
        return False
    cursor = index + 2
    if cursor < len(tokens) and _is_keyword(tokens[cursor], "only"):
        cursor += 1
    return _qualified_owned_name(tokens, cursor, "users")


def _policy_matches(sql: str) -> tuple[tuple[int, str], ...]:
    matches: list[tuple[int, str]] = []
    tokens = _sql_tokens(sql)
    for index, token in enumerate(tokens):
        ddl_rule = _match_ddl(tokens, index)
        if ddl_rule is not None:
            matches.append((token.start, ddl_rule))
        if _match_auth_users_insert(tokens, index):
            matches.append((token.start, AUTH_USERS_INSERT_RULE))
    return tuple(matches)


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
        sql = _mask_comments_preserving_literals(path.read_text(encoding="utf-8"))
        for start, rule in _policy_matches(sql):
            line = sql.count("\n", 0, start) + 1
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
