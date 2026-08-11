#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence


BRANCH_RE = re.compile(
    r"^(feat|fix|build|chore|docs|style|refactor|test|release)/([1-9][0-9]*)-[a-z0-9]+(?:-[a-z0-9]+)*$"
)
PROTECTED_BRANCHES = {"main", "develop"}
APPROVAL_FIELDS = {
    "issueNumber",
    "branch",
    "headSha",
    "verdict",
    "reviewedAt",
    "qualityGateSha",
    "requiredChangesCount",
}


class RecorderError(RuntimeError):
    """승인 상태 기록 계약을 만족하지 못했을 때 발생한다."""


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="독립 Reviewer의 검증 완료 결과를 안전하게 기록합니다."
    )
    parser.add_argument("--issue", required=True, type=_positive_integer)
    parser.add_argument(
        "--verdict",
        required=True,
        choices=("APPROVED", "CHANGES_REQUESTED"),
    )
    parser.add_argument("--findings-count", required=True, type=_nonnegative_integer)
    parser.add_argument(
        "--required-changes-count", required=True, type=_nonnegative_integer
    )
    return parser


def _positive_integer(value: str) -> int:
    parsed = _nonnegative_integer(value)
    if parsed == 0:
        raise argparse.ArgumentTypeError("Issue 번호는 양수여야 합니다.")
    return parsed


def _nonnegative_integer(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("0 이상의 정수가 필요합니다.") from exc
    if parsed < 0:
        raise argparse.ArgumentTypeError("0 이상의 정수가 필요합니다.")
    return parsed


def _safe_branch_name(branch: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "__", branch)


def _git(root: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=root,
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RecorderError(f"Git 상태를 확인할 수 없습니다: {detail}")
    return completed.stdout.strip()


def _current_context(root: Path, issue: int) -> tuple[str, str]:
    branch = _git(root, "branch", "--show-current")
    match = BRANCH_RE.fullmatch(branch)
    if not branch or branch in PROTECTED_BRANCHES or match is None:
        raise RecorderError("현재 HEAD는 규칙에 맞는 Issue 작업 브랜치여야 합니다.")
    if int(match.group(2)) != issue:
        raise RecorderError("입력한 Issue 번호와 현재 브랜치 Issue 번호가 다릅니다.")

    sha = _git(root, "rev-parse", "HEAD")
    status = _git(root, "status", "--porcelain")
    if status:
        raise RecorderError("작업 트리가 깨끗하지 않습니다. 모든 변경을 먼저 커밋하세요.")

    remote_ref = f"refs/remotes/origin/{branch}"
    probe = subprocess.run(
        ["git", "show-ref", "--verify", "--quiet", remote_ref],
        cwd=root,
        text=True,
        capture_output=True,
        check=False,
    )
    if probe.returncode != 0:
        raise RecorderError("현재 작업 브랜치의 원격 브랜치가 없습니다.")
    remote_sha = _git(root, "rev-parse", remote_ref)
    if remote_sha != sha:
        raise RecorderError("로컬 HEAD와 원격 작업 브랜치 HEAD가 일치하지 않습니다.")
    return branch, sha


def _assert_path_chain_is_local(root: Path, path: Path) -> None:
    try:
        path.relative_to(root)
    except ValueError as exc:
        raise RecorderError("승인 상태 경로가 저장소 외부를 가리킵니다.") from exc

    relative = path.relative_to(root)
    current = root
    for component in relative.parts:
        current = current / component
        if current.is_symlink():
            raise RecorderError(f"승인 상태 경로에 심볼릭 링크를 사용할 수 없습니다: {current}")


def _load_object(path: Path, label: str) -> dict[str, Any]:
    if path.is_symlink():
        raise RecorderError(f"{label}에 심볼릭 링크를 사용할 수 없습니다.")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise RecorderError(f"{label} 파일이 없습니다.") from exc
    except (OSError, json.JSONDecodeError) as exc:
        raise RecorderError(f"{label} JSON이 올바르지 않습니다.") from exc
    if not isinstance(payload, dict):
        raise RecorderError(f"{label} JSON은 object여야 합니다.")
    return payload


def _validate_quality_gate(root: Path, branch: str, sha: str) -> None:
    path = (
        root
        / ".codex"
        / "state"
        / "quality-gates"
        / f"{_safe_branch_name(branch)}.json"
    )
    _assert_path_chain_is_local(root, path)
    gate = _load_object(path, "품질 게이트 상태")
    if gate.get("branch") != branch:
        raise RecorderError("품질 게이트 브랜치가 현재 브랜치와 다릅니다.")
    if gate.get("headSha") != sha:
        raise RecorderError("품질 게이트가 현재 HEAD에서 실행되지 않았습니다.")
    if gate.get("result") != "SUCCESS":
        raise RecorderError("품질 게이트 결과가 SUCCESS가 아닙니다.")


def _review_path(root: Path, branch: str) -> Path:
    return (
        root
        / ".codex"
        / "state"
        / "reviews"
        / f"{_safe_branch_name(branch)}.json"
    )


def _load_existing_review(path: Path) -> dict[str, Any] | None:
    if path.is_symlink():
        raise RecorderError("승인 상태 파일에 심볼릭 링크를 사용할 수 없습니다.")
    if not path.exists():
        return None
    review = _load_object(path, "기존 승인 상태")
    if not _is_valid_approval(review):
        raise RecorderError("기존 승인 상태 JSON schema가 올바르지 않습니다.")
    return review


def _is_valid_approval(review: dict[str, Any]) -> bool:
    if set(review) != APPROVAL_FIELDS:
        return False
    issue = review.get("issueNumber")
    required_changes = review.get("requiredChangesCount")
    if not isinstance(issue, int) or isinstance(issue, bool) or issue <= 0:
        return False
    if not isinstance(required_changes, int) or isinstance(required_changes, bool):
        return False
    if required_changes != 0 or review.get("verdict") != "APPROVED":
        return False
    branch = review.get("branch")
    if not isinstance(branch, str) or BRANCH_RE.fullmatch(branch) is None:
        return False
    sha_pattern = re.compile(r"[0-9a-f]{40}")
    if not isinstance(review.get("headSha"), str) or sha_pattern.fullmatch(review["headSha"]) is None:
        return False
    if (
        not isinstance(review.get("qualityGateSha"), str)
        or sha_pattern.fullmatch(review["qualityGateSha"]) is None
    ):
        return False
    reviewed_at = review.get("reviewedAt")
    if not isinstance(reviewed_at, str):
        return False
    try:
        parsed_time = datetime.datetime.fromisoformat(reviewed_at)
    except ValueError:
        return False
    return parsed_time.tzinfo is not None and parsed_time.utcoffset() is not None


def _write_atomically(root: Path, path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    _assert_path_chain_is_local(root, path)
    descriptor = -1
    temporary_path: Path | None = None
    try:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=".review-state-",
            suffix=".tmp",
            dir=path.parent,
        )
        temporary_path = Path(temporary_name)
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as temporary_file:
            descriptor = -1
            json.dump(payload, temporary_file, ensure_ascii=False, indent=2)
            temporary_file.write("\n")
            temporary_file.flush()
            os.fsync(temporary_file.fileno())
        os.replace(temporary_path, path)
        temporary_path = None
    except OSError as exc:
        raise RecorderError("승인 상태 파일을 원자적으로 기록하지 못했습니다.") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def record_review_state(
    *,
    root: Path,
    issue: int,
    verdict: str,
    findings_count: int,
    required_changes_count: int,
    reviewed_at: datetime.datetime,
) -> Path | None:
    root = root.resolve()
    if verdict == "APPROVED":
        if findings_count != 0:
            raise RecorderError("APPROVED는 finding 0건일 때만 기록할 수 있습니다.")
        if required_changes_count != 0:
            raise RecorderError("APPROVED의 requiredChangesCount는 0이어야 합니다.")
    elif verdict == "CHANGES_REQUESTED":
        if (
            findings_count <= 0
            or required_changes_count <= 0
            or findings_count != required_changes_count
        ):
            raise RecorderError(
                "CHANGES_REQUESTED는 동일한 양수 finding 수와 requiredChangesCount가 필요합니다."
            )
    else:
        raise RecorderError("지원하지 않는 Reviewer verdict입니다.")

    branch, sha = _current_context(root, issue)
    path = _review_path(root, branch)
    _assert_path_chain_is_local(root, path)
    existing = _load_existing_review(path)
    if existing is not None and (
        existing.get("issueNumber") != issue or existing.get("branch") != branch
    ):
        raise RecorderError("기존 승인 상태의 Issue 또는 브랜치가 현재 작업과 다릅니다.")

    if verdict == "CHANGES_REQUESTED":
        if existing is not None:
            path.unlink()
        return None

    _validate_quality_gate(root, branch, sha)
    if reviewed_at.tzinfo is None or reviewed_at.utcoffset() is None:
        raise RecorderError("reviewedAt은 timezone이 있는 시각이어야 합니다.")
    payload = {
        "issueNumber": issue,
        "branch": branch,
        "headSha": sha,
        "verdict": "APPROVED",
        "reviewedAt": reviewed_at.astimezone(datetime.timezone.utc).isoformat(),
        "qualityGateSha": sha,
        "requiredChangesCount": 0,
    }
    if existing == payload:
        return path
    if (
        existing is not None
        and existing.get("issueNumber") == issue
        and existing.get("branch") == branch
        and existing.get("headSha") == sha
        and existing.get("qualityGateSha") == sha
        and existing.get("verdict") == "APPROVED"
        and existing.get("requiredChangesCount") == 0
    ):
        return path

    _write_atomically(root, path, payload)
    return path


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    root = Path(__file__).resolve().parents[1]
    try:
        path = record_review_state(
            root=root,
            issue=args.issue,
            verdict=args.verdict,
            findings_count=args.findings_count,
            required_changes_count=args.required_changes_count,
            reviewed_at=datetime.datetime.now(datetime.timezone.utc),
        )
    except RecorderError as exc:
        print(f"승인 상태 기록 실패: {exc}", file=sys.stderr)
        return 1

    if path is None:
        print("현재 브랜치의 stale 승인 상태 정리가 완료되었습니다.")
    else:
        print(f"Reviewer 승인 상태 기록 완료: {path.relative_to(root)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
