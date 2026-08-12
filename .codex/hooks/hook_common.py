from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


ALLOWED_TYPES = ("feat", "fix", "build", "chore", "docs", "style", "refactor", "test", "release")
PROTECTED_BRANCHES = {"main", "develop"}
BRANCH_RE = re.compile(
    r"^(feat|fix|build|chore|docs|style|refactor|test|release)/([1-9][0-9]*)-[a-z0-9]+(?:-[a-z0-9]+)*$"
)
COMMIT_RE = re.compile(
    r"^(feat|fix|build|chore|docs|style|refactor|test|release): #([1-9][0-9]*) .+$"
)


def read_input() -> dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    try:
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else {"value": parsed}
    except json.JSONDecodeError:
        return {"raw": raw}


def emit(payload: dict[str, Any]) -> None:
    print(json.dumps(payload, ensure_ascii=False))


def allow(message: str = "") -> None:
    payload: dict[str, Any] = {"continue": True}
    if message:
        payload.update({"additionalContext": message, "systemMessage": message})
    emit(payload)


def block(reason: str) -> None:
    emit(
        {
            "continue": False,
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
            "decision": "block",
            "reason": reason,
            "systemMessage": reason,
            "additionalContext": reason,
        }
    )


def extract_strings(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        result: list[str] = []
        for item in value.values():
            result.extend(extract_strings(item))
        return result
    if isinstance(value, list):
        result = []
        for item in value:
            result.extend(extract_strings(item))
        return result
    return []


def event_text(data: dict[str, Any]) -> str:
    return "\n".join(extract_strings(data))


def command_text(data: dict[str, Any]) -> str:
    candidates: list[str] = []
    for key in ("cmd", "command", "raw"):
        value = data.get(key)
        if isinstance(value, str):
            candidates.append(value)
    for key in ("tool_input", "parameters", "input"):
        candidates.extend(extract_strings(data.get(key)))
    return "\n".join(candidates)


def repo_root() -> Path:
    current = Path.cwd().resolve()
    for candidate in (current, *current.parents):
        if (candidate / ".git").exists():
            return candidate
    return current


def run(args: list[str], root: Path, timeout: int = 5) -> tuple[int, str, str]:
    try:
        completed = subprocess.run(
            args,
            cwd=root,
            text=True,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
        return completed.returncode, completed.stdout.strip(), completed.stderr.strip()
    except Exception as exc:
        return 1, "", str(exc)


def current_branch(root: Path) -> str:
    head_path = root / ".git" / "HEAD"
    try:
        head = head_path.read_text(encoding="utf-8").strip()
        prefix = "ref: refs/heads/"
        if head.startswith(prefix):
            return head[len(prefix) :]
    except OSError:
        pass
    code, stdout, _ = run(["git", "branch", "--show-current"], root)
    return stdout if code == 0 else ""


def head_sha(root: Path) -> str:
    code, stdout, _ = run(["git", "rev-parse", "HEAD"], root)
    return stdout if code == 0 else ""


def is_dirty(root: Path) -> bool:
    code, stdout, _ = run(["git", "status", "--porcelain"], root)
    return code != 0 or bool(stdout)


def branch_issue(branch: str) -> str | None:
    match = BRANCH_RE.fullmatch(branch)
    return match.group(2) if match else None


def safe_branch_name(branch: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "__", branch)


def state_path(root: Path, kind: str, branch: str) -> Path:
    return root / ".codex" / "state" / kind / f"{safe_branch_name(branch)}.json"


def load_json(path: Path) -> dict[str, Any] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else None
    except (OSError, json.JSONDecodeError):
        return None


def validate_pr_state(
    root: Path,
    branch: str,
    sha: str,
    base: str,
    dirty: bool,
    remote_exists: bool,
) -> list[str]:
    errors: list[str] = []
    match = BRANCH_RE.fullmatch(branch)
    if branch in PROTECTED_BRANCHES or not match:
        errors.append("현재 브랜치가 PR 생성 규칙에 맞지 않습니다.")
        return errors
    issue = match.group(2)
    branch_type = match.group(1)
    if dirty:
        errors.append("작업 트리가 깨끗하지 않습니다. 모든 변경을 검토하고 커밋하세요.")
    if not remote_exists:
        errors.append("현재 브랜치의 원격 브랜치가 없습니다. 품질 게이트 통과 후 먼저 push하세요.")
    expected_base = "main" if branch_type == "release" else "develop"
    if base != expected_base:
        errors.append(f"`{branch_type}` 브랜치의 PR base는 `{expected_base}`여야 합니다.")

    gate = load_json(state_path(root, "quality-gates", branch))
    if not gate or gate.get("headSha") != sha or gate.get("result") != "SUCCESS":
        errors.append("현재 HEAD에 대한 품질 게이트 성공 기록이 없습니다. quality-gate를 다시 실행하세요.")
    elif gate.get("branch") != branch:
        errors.append("품질 게이트 기록의 브랜치가 현재 브랜치와 다릅니다.")

    review = load_json(state_path(root, "reviews", branch))
    if not review or review.get("headSha") != sha or review.get("verdict") != "APPROVED":
        errors.append("현재 HEAD에 대한 Reviewer APPROVED 기록이 없습니다. pre-pr-review를 다시 실행하세요.")
    elif review.get("requiredChangesCount") != 0:
        errors.append("Reviewer 필수 수정사항이 남아 있습니다.")
    if review and review.get("branch") != branch:
        errors.append("Reviewer 승인 브랜치가 현재 브랜치와 다릅니다.")
    if review and review.get("qualityGateSha") != sha:
        errors.append("Reviewer 승인 품질 게이트 SHA가 현재 HEAD와 다릅니다.")
    if review and str(review.get("issueNumber")) != issue:
        errors.append("Reviewer 승인 Issue 번호와 브랜치 Issue 번호가 다릅니다.")
    return errors


def local_repo_state(root: Path, base: str) -> tuple[str, str, bool, bool, list[str]]:
    branch = current_branch(root)
    sha = head_sha(root)
    dirty = is_dirty(root)
    remote_exists = False
    if branch:
        code, _, _ = run(["git", "show-ref", "--verify", "--quiet", f"refs/remotes/origin/{branch}"], root)
        remote_exists = code == 0
    return branch, sha, dirty, remote_exists, validate_pr_state(root, branch, sha, base, dirty, remote_exists)
