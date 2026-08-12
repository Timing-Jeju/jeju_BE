#!/usr/bin/env python3
from __future__ import annotations

import re
import shlex

from hook_common import (
    BRANCH_RE,
    COMMIT_RE,
    PROTECTED_BRANCHES,
    allow,
    block,
    branch_issue,
    command_text,
    current_branch,
    local_repo_state,
    read_input,
    repo_root,
)


DESTRUCTIVE_PATTERNS = (
    r"(?:^|[;&|]\s*)git\s+reset\s+--hard(?:\s|$)",
    r"(?:^|[;&|]\s*)git\s+clean\s+-[^\s]*f[^\s]*d[^\s]*(?:\s|$)",
    r"(?:^|[;&|]\s*)git\s+checkout\s+--\s+\.(?:\s|$)",
    r"(?:^|[;&|]\s*)git\s+restore\s+\.(?:\s|$)",
)
REVIEW_STATE_PATH_RE = re.compile(r"\.codex[/\\]state[/\\]reviews(?:[/\\]|$)", re.IGNORECASE)
REVIEW_STATE_RECORDER_RE = re.compile(
    r"scripts[/\\]record_review_state\.py",
    re.IGNORECASE,
)
REVIEW_STATE_RECORDER_OPTIONS = {
    "--issue",
    "--verdict",
    "--findings-count",
    "--required-changes-count",
}


def is_standalone_review_state_recorder(command: str) -> bool:
    if re.search(r"[;&|<>\r\n]", command):
        return False
    try:
        tokens = shlex.split(command, posix=True)
    except ValueError:
        return False

    if len(tokens) >= 2 and tokens[:2] == ["python3", "scripts/record_review_state.py"]:
        arguments = tokens[2:]
    elif len(tokens) >= 3 and tokens[:3] == ["py", "-3", "scripts/record_review_state.py"]:
        arguments = tokens[3:]
    else:
        return False
    if len(arguments) != 8:
        return False

    values: dict[str, str] = {}
    for index in range(0, len(arguments), 2):
        option, value = arguments[index : index + 2]
        if option not in REVIEW_STATE_RECORDER_OPTIONS or option in values:
            return False
        values[option] = value
    if set(values) != REVIEW_STATE_RECORDER_OPTIONS:
        return False
    if values["--verdict"] not in {"APPROVED", "CHANGES_REQUESTED"}:
        return False
    if not values["--issue"].isdigit() or int(values["--issue"]) <= 0:
        return False
    return all(
        values[option].isdigit()
        for option in ("--findings-count", "--required-changes-count")
    )


def extract_commit_message(command: str) -> str | None:
    match = re.search(r"\bgit\s+commit\b.*?(?:-m|--message)(?:=|\s+)(['\"])(.*?)\1", command, re.DOTALL)
    if match:
        return match.group(2)
    return None


def evaluate_command(
    command: str,
    branch: str,
    root=None,
    sha: str = "",
    dirty: bool = False,
    remote_exists: bool = True,
) -> str | None:
    lowered = command.lower()
    if REVIEW_STATE_RECORDER_RE.search(command):
        if is_standalone_review_state_recorder(command):
            return None
        return "승인 상태 기록 명령은 추가 명령이나 우회 없이 공식 형식으로 단독 실행해야 합니다."
    if REVIEW_STATE_PATH_RE.search(command):
        mutation_marker = re.search(
            r"apply_patch|update file|add file|delete file|[<>]|\brm\b|\bunlink\b|\bmv\b|\bcp\b|\btee\b|\btouch\b|\btruncate\b|\bdd\b|\bln\b|\bchmod\b|\binstall\b|\bset-content\b|\bremove-item\b|sed\s+-i|(?:python3|py\s+-3|node|ruby|perl)\s+(?:-c|-e)",
            command,
            re.IGNORECASE,
        )
        if mutation_marker:
            return "승인 상태 파일은 직접 조작할 수 없습니다. 검증된 Reviewer 기록 명령만 사용하세요."
    for pattern in DESTRUCTIVE_PATTERNS:
        if re.search(pattern, command, re.IGNORECASE):
            return "복구가 어려운 Git 명령은 정책상 차단됩니다. 안전한 대안을 사용하세요."

    if re.search(r"\bgit\s+push\b[^\n]*(?:--force(?:-with-lease)?|-f)(?:\s|$)", command):
        return "force push는 금지됩니다."

    if re.search(r"\bgit\s+commit\b", command):
        if branch in PROTECTED_BRANCHES:
            return f"현재 브랜치는 {branch}입니다. Issue 브랜치를 생성한 뒤 커밋하세요."
        if not BRANCH_RE.fullmatch(branch):
            return "현재 브랜치 이름이 규칙에 맞지 않아 커밋할 수 없습니다."
        message = extract_commit_message(command)
        if message is not None:
            match = COMMIT_RE.fullmatch(message)
            if not match:
                return "커밋 메시지는 `type: #이슈번호 요약` 형식이어야 합니다."
            if match.group(2) != branch_issue(branch):
                return "커밋 메시지의 Issue 번호가 현재 브랜치와 다릅니다."

    if re.search(r"\bgit\s+push\b", command):
        if branch in PROTECTED_BRANCHES:
            return f"{branch} 브랜치 직접 push는 금지됩니다. PR을 사용하세요."
        if re.search(r"\bgit\s+push\b[^\n]*(?:\bmain\b|\bdevelop\b|HEAD:(?:main|develop)\b)", command):
            return "main/develop으로 직접 push할 수 없습니다. 현재 작업 브랜치만 push하세요."

    if re.search(r"\bgh\s+pr\s+create\b", command):
        return "raw `gh pr create`는 허용되지 않습니다. `scripts/create-pr.sh` 또는 PowerShell 스크립트를 사용하세요."

    if re.search(r"scripts[/\\]create-pr\.(?:sh|ps1)", lowered):
        base_match = re.search(r"--base(?:=|\s+)([A-Za-z0-9._/-]+)", command)
        base = base_match.group(1) if base_match else ("main" if branch.startswith("release/") else "develop")
        if root is None:
            return "PR 상태 검증을 위한 저장소 경로가 없습니다."
        errors = __import__("hook_common").validate_pr_state(root, branch, sha, base, dirty, remote_exists)
        if errors:
            return "\n".join(errors)
    return None


def main() -> None:
    data = read_input()
    root = repo_root()
    command = command_text(data)
    branch = current_branch(root)
    if re.search(r"scripts[/\\]create-pr\.(?:sh|ps1)", command.lower()):
        base_match = re.search(r"--base(?:=|\s+)([A-Za-z0-9._/-]+)", command)
        base = base_match.group(1) if base_match else ("main" if branch.startswith("release/") else "develop")
        _, sha, dirty, remote_exists, errors = local_repo_state(root, base)
        reason = "\n".join(errors) if errors else evaluate_command(command, branch, root, sha, dirty, remote_exists)
    else:
        reason = evaluate_command(command, branch)
    if reason:
        block(reason)
    else:
        allow()


if __name__ == "__main__":
    main()
