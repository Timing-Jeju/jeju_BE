#!/usr/bin/env python3
"""원자 cutover를 우회하는 Supabase 원격 migration 실행을 차단한다."""

from __future__ import annotations

import ast
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
IGNORED_PARTS = frozenset({".git", "__pycache__", "build", "node_modules", "tests"})
MAKEFILE_NAMES = frozenset({"GNUmakefile", "Makefile", "makefile"})
PYTHON_PROCESS_CALLS = frozenset(
    {
        "asyncio.create_subprocess_exec",
        "asyncio.create_subprocess_shell",
        "os.popen",
        "os.system",
        "subprocess.Popen",
        "subprocess.call",
        "subprocess.check_call",
        "subprocess.check_output",
        "subprocess.run",
    }
)

_DIRECT_RUNNER = (
    r"(?:"
    r"(?:npx|bunx)\s+(?:--yes\s+)?supabase(?:@[^\s\"']+)?"
    r"|pnpm\s+(?:dlx|exec)\s+supabase(?:@[^\s\"']+)?"
    r"|(?:[^\s\"']*/)?supabase(?:@[^\s\"']+)?"
    r")"
)
_VARIABLE_RUNNER = r'''(?:["']?\$\{?[a-z_][a-z0-9_]*\}?["']?)'''
_SHELL_PUSH = re.compile(
    r"(?ix)"
    r"(?:^|(?:&&|\|\||;|\|)\s*|\brun\s*:\s*)"
    r"\s*"
    r"(?:env\s+|command\s+)*"
    r"(?:" + _DIRECT_RUNNER + r"|" + _VARIABLE_RUNNER + r")"
    r"[\"']?\s+db\s+push\b"
)


@dataclass(frozen=True)
class Violation:
    path: Path
    line: int


def _is_ignored(path: Path, root: Path) -> bool:
    try:
        parts = path.relative_to(root).parts
    except ValueError:
        return True
    return any(part in IGNORED_PARTS for part in parts)


def _candidate_paths(root: Path) -> tuple[Path, ...]:
    candidates: set[Path] = set()
    for directory in (
        root / "scripts",
        root / ".github" / "workflows",
        root / ".github" / "actions",
    ):
        if directory.is_dir():
            candidates.update(
                path
                for path in directory.rglob("*")
                if path.is_file() and not _is_ignored(path, root)
            )
    candidates.update(
        path
        for path in root.rglob("*")
        if path.is_file()
        and not _is_ignored(path, root)
        and (
            path.name in MAKEFILE_NAMES
            or path.name == "package.json"
            or path.suffix == ".mk"
        )
    )
    return tuple(sorted(candidates, key=lambda path: path.as_posix()))


def _without_comment(line: str) -> str:
    """따옴표 밖의 shell·YAML 주석을 검사 대상에서 제거한다."""
    quote: str | None = None
    escaped = False
    for index, character in enumerate(line):
        if escaped:
            escaped = False
            continue
        if character == "\\" and quote is not None:
            escaped = True
            continue
        if quote is not None:
            if character == quote:
                quote = None
            continue
        if character in ("'", '"'):
            quote = character
        elif character == "#":
            return line[:index]
    return line


def _call_name(function: ast.expr) -> str | None:
    names: list[str] = []
    current = function
    while isinstance(current, ast.Attribute):
        names.append(current.attr)
        current = current.value
    if not isinstance(current, ast.Name):
        return None
    names.append(current.id)
    return ".".join(reversed(names))


def _string_value(node: ast.expr) -> str | None:
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    return None


def _python_command_is_push(command: ast.expr) -> bool:
    shell_command = _string_value(command)
    if shell_command is not None:
        return _SHELL_PUSH.search(shell_command) is not None
    if not isinstance(command, (ast.List, ast.Tuple)):
        return False
    arguments = [_string_value(element) for element in command.elts]
    return any(
        arguments[index : index + 2] == ["db", "push"] and index > 0
        for index in range(len(arguments) - 1)
    )


def _python_violations(path: Path, source: str) -> tuple[Violation, ...]:
    try:
        tree = ast.parse(source, filename=str(path))
    except SyntaxError:
        return ()
    violations: list[Violation] = []
    for node in ast.walk(tree):
        if (
            not isinstance(node, ast.Call)
            or _call_name(node.func) not in PYTHON_PROCESS_CALLS
        ):
            continue
        command = node.args[0] if node.args else next(
            (
                keyword.value
                for keyword in node.keywords
                if keyword.arg in {"args", "cmd", "command"}
            ),
            None,
        )
        if _call_name(node.func) == "asyncio.create_subprocess_exec":
            command = ast.List(elts=node.args, ctx=ast.Load())
        if command is not None and _python_command_is_push(command):
            violations.append(Violation(path, node.lineno))
    return tuple(violations)


def _package_violations(path: Path, source: str) -> tuple[Violation, ...]:
    try:
        package = json.loads(source)
    except (json.JSONDecodeError, TypeError):
        return ()
    scripts = package.get("scripts", {})
    if not isinstance(scripts, dict):
        return ()
    lines = source.splitlines()
    violations: list[Violation] = []
    for name, command in scripts.items():
        if (
            not isinstance(name, str)
            or not isinstance(command, str)
            or not _SHELL_PUSH.search(command)
        ):
            continue
        key_pattern = re.compile(rf"^\s*{re.escape(json.dumps(name))}\s*:")
        line = next(
            (
                number
                for number, text in enumerate(lines, start=1)
                if key_pattern.search(text)
            ),
            1,
        )
        violations.append(Violation(path, line))
    return tuple(violations)


def _text_violations(path: Path, source: str) -> tuple[Violation, ...]:
    violations: list[Violation] = []
    logical_lines: list[tuple[int, str]] = []
    start_line = 1
    buffered = ""
    for line_number, line in enumerate(source.splitlines(), start=1):
        code = _without_comment(line).rstrip()
        if not buffered:
            start_line = line_number
        if code.endswith("\\"):
            buffered += code[:-1] + " "
            continue
        logical_lines.append((start_line, buffered + code))
        buffered = ""
    if buffered:
        logical_lines.append((start_line, buffered))
    for line_number, code in logical_lines:
        if path.name in MAKEFILE_NAMES or path.suffix == ".mk":
            code = re.sub(r"^\s*[@+\-]+\s*", "", code)
        if _SHELL_PUSH.search(code):
            violations.append(Violation(path, line_number))
    return tuple(violations)


def find_violations(root: Path = ROOT) -> tuple[Violation, ...]:
    violations: list[Violation] = []
    for path in _candidate_paths(root):
        relative = path.relative_to(root)
        try:
            source = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if path.suffix.casefold() == ".py":
            found = _python_violations(relative, source)
        elif path.name == "package.json":
            found = _package_violations(relative, source)
        else:
            found = _text_violations(relative, source)
        violations.extend(found)
    return tuple(
        sorted(set(violations), key=lambda item: (item.path.as_posix(), item.line))
    )


def main() -> int:
    violations = find_violations()
    if not violations:
        print("Supabase 배포 진입점 정책 검사 통과")
        return 0
    for violation in violations:
        print(
            f"{violation.path}:{violation.line}: 원자 cutover를 우회하는 "
            "supabase db push를 사용할 수 없습니다.",
            file=sys.stderr,
        )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
