from __future__ import annotations

import importlib.util
import io
import json
import os
import stat
import subprocess
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock
from contextlib import redirect_stderr


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "record_review_state.py"


def load_recorder():
    spec = importlib.util.spec_from_file_location("record_review_state", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("승인 상태 기록기 모듈을 불러올 수 없습니다.")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


recorder = load_recorder()


class ReviewApprovalRecorderTest(unittest.TestCase):
    BRANCH = "fix/126-review-approval-recorder"
    ISSUE = 126
    NOW = datetime(2026, 8, 12, 3, 4, 5, tzinfo=timezone.utc)

    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self._git("init", "-b", "develop")
        self._git("config", "user.name", "Reviewer Test")
        self._git("config", "user.email", "reviewer@example.invalid")
        (self.root / "tracked.txt").write_text("base\n", encoding="utf-8")
        (self.root / ".gitignore").write_text(".codex/state/\n", encoding="utf-8")
        self._git("add", "tracked.txt", ".gitignore")
        self._git("commit", "-m", "chore: base")
        self._git("switch", "-c", self.BRANCH)
        self.sha = self._git("rev-parse", "HEAD")
        self._set_remote_head(self.sha)
        self._write_quality_gate()

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_approved_review_is_recorded_with_exact_schema(self):
        path = self._record_approved()

        self.assertEqual(self._review_path().resolve(), path)
        self.assertFalse(path.is_symlink())
        self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        self.assertEqual(
            {
                "issueNumber": self.ISSUE,
                "branch": self.BRANCH,
                "headSha": self.sha,
                "verdict": "APPROVED",
                "reviewedAt": self.NOW.isoformat(),
                "qualityGateSha": self.sha,
                "requiredChangesCount": 0,
            },
            json.loads(path.read_text(encoding="utf-8")),
        )

    def test_same_head_approval_is_idempotent_and_preserves_reviewed_at(self):
        path = self._record_approved()
        first_content = path.read_bytes()

        recorder.record_review_state(
            root=self.root,
            issue=self.ISSUE,
            verdict="APPROVED",
            findings_count=0,
            required_changes_count=0,
            reviewed_at=datetime(2026, 8, 12, 6, 7, 8, tzinfo=timezone.utc),
        )

        self.assertEqual(first_content, path.read_bytes())

    def test_stale_valid_approval_is_replaced_for_current_reviewed_head(self):
        self._write_review(
            {
                "issueNumber": self.ISSUE,
                "branch": self.BRANCH,
                "headSha": "0" * 40,
                "verdict": "APPROVED",
                "reviewedAt": "2026-08-11T00:00:00+00:00",
                "qualityGateSha": "0" * 40,
                "requiredChangesCount": 0,
            }
        )

        path = self._record_approved()

        self.assertEqual(self.sha, json.loads(path.read_text(encoding="utf-8"))["headSha"])

    def test_existing_approval_for_another_issue_or_branch_is_not_overwritten(self):
        for field, value in (("issueNumber", 999), ("branch", "fix/999-other")):
            with self.subTest(field=field):
                payload = {
                    "issueNumber": self.ISSUE,
                    "branch": self.BRANCH,
                    "headSha": "0" * 40,
                    "verdict": "APPROVED",
                    "reviewedAt": self.NOW.isoformat(),
                    "qualityGateSha": "0" * 40,
                    "requiredChangesCount": 0,
                }
                payload[field] = value
                self._write_review(payload)
                before = self._review_path().read_bytes()

                with self.assertRaisesRegex(recorder.RecorderError, "Issue 또는 브랜치"):
                    self._record_approved()

                self.assertEqual(before, self._review_path().read_bytes())
                self._review_path().unlink()

    def test_existing_approval_with_invalid_schema_is_rejected(self):
        base = {
            "issueNumber": self.ISSUE,
            "branch": self.BRANCH,
            "headSha": "0" * 40,
            "verdict": "APPROVED",
            "reviewedAt": self.NOW.isoformat(),
            "qualityGateSha": "0" * 40,
            "requiredChangesCount": 0,
        }
        cases = (
            ("reviewedAt", 123),
            ("headSha", "not-a-sha"),
            ("verdict", "CHANGES_REQUESTED"),
            ("requiredChangesCount", True),
        )
        for field, value in cases:
            with self.subTest(field=field):
                payload = dict(base)
                payload[field] = value
                self._write_review(payload)

                with self.assertRaisesRegex(recorder.RecorderError, "schema"):
                    self._record_approved()

                self._review_path().unlink()

    def test_protected_and_detached_heads_are_rejected(self):
        for checkout in (("switch", "develop"), ("switch", "--detach", self.sha)):
            with self.subTest(checkout=checkout):
                self._git(*checkout)
                with self.assertRaisesRegex(recorder.RecorderError, "작업 브랜치"):
                    self._record_approved()
                self.assertFalse(self._review_path().exists())
                self._git("switch", self.BRANCH)

    def test_issue_mismatch_is_rejected(self):
        with self.assertRaisesRegex(recorder.RecorderError, "Issue 번호"):
            recorder.record_review_state(
                root=self.root,
                issue=127,
                verdict="APPROVED",
                findings_count=0,
                required_changes_count=0,
                reviewed_at=self.NOW,
            )

        self.assertFalse(self._review_path().exists())

    def test_missing_or_mismatched_remote_head_is_rejected(self):
        self._git("update-ref", "-d", f"refs/remotes/origin/{self.BRANCH}")
        with self.assertRaisesRegex(recorder.RecorderError, "원격"):
            self._record_approved()

        self._git("update-ref", f"refs/remotes/origin/{self.BRANCH}", "HEAD~0")
        (self.root / "tracked.txt").write_text("next\n", encoding="utf-8")
        self._git("add", "tracked.txt")
        self._git("commit", "-m", "fix: #126 next")
        self._write_quality_gate(sha=self._git("rev-parse", "HEAD"))
        with self.assertRaisesRegex(recorder.RecorderError, "일치하지"):
            self._record_approved()

    def test_dirty_worktree_is_rejected(self):
        (self.root / "tracked.txt").write_text("dirty\n", encoding="utf-8")

        with self.assertRaisesRegex(recorder.RecorderError, "깨끗하지"):
            self._record_approved()

    def test_missing_failed_stale_or_wrong_branch_quality_gate_is_rejected(self):
        cases = (
            (None, "품질 게이트"),
            ({"branch": self.BRANCH, "headSha": self.sha, "result": "FAILED"}, "SUCCESS"),
            ({"branch": self.BRANCH, "headSha": "0" * 40, "result": "SUCCESS"}, "현재 HEAD"),
            ({"branch": "fix/999-other", "headSha": self.sha, "result": "SUCCESS"}, "브랜치"),
        )
        for payload, message in cases:
            with self.subTest(payload=payload):
                path = self._quality_path()
                if payload is None:
                    path.unlink(missing_ok=True)
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(json.dumps(payload), encoding="utf-8")
                with self.assertRaisesRegex(recorder.RecorderError, message):
                    self._record_approved()
                self.assertFalse(self._review_path().exists())
                self._write_quality_gate()

    def test_malformed_or_symlink_state_files_are_rejected(self):
        quality_path = self._quality_path()
        quality_path.write_text("not-json", encoding="utf-8")
        with self.assertRaisesRegex(recorder.RecorderError, "JSON"):
            self._record_approved()
        self._write_quality_gate()

        external = self.root / ".codex" / "state" / "external.json"
        external.parent.mkdir(parents=True, exist_ok=True)
        external.write_text("{}", encoding="utf-8")
        quality_path.unlink()
        quality_path.symlink_to(external)
        with self.assertRaisesRegex(recorder.RecorderError, "심볼릭 링크"):
            self._record_approved()
        quality_path.unlink()
        self._write_quality_gate()

        review_path = self._review_path()
        review_path.parent.mkdir(parents=True, exist_ok=True)
        review_path.symlink_to(external)
        with self.assertRaisesRegex(recorder.RecorderError, "심볼릭 링크"):
            self._record_approved()
        review_path.unlink()
        review_path.write_text("not-json", encoding="utf-8")
        with self.assertRaisesRegex(recorder.RecorderError, "기존 승인 상태 JSON"):
            self._record_approved()

    def test_symlink_review_directory_cannot_redirect_output(self):
        reviews = self.root / ".codex" / "state" / "reviews"
        external = self.root / "outside"
        external.mkdir()
        reviews.parent.mkdir(parents=True, exist_ok=True)
        reviews.symlink_to(external, target_is_directory=True)

        with self.assertRaisesRegex(recorder.RecorderError, "심볼릭 링크"):
            self._record_approved()

        self.assertEqual([], list(external.iterdir()))

    def test_approved_rejects_nonzero_findings_required_changes_and_other_verdict(self):
        cases = (
            ("APPROVED", 1, 0, "finding"),
            ("APPROVED", 0, 1, "requiredChangesCount"),
            ("CHANGES_REQUESTED", 0, 0, "CHANGES_REQUESTED"),
        )
        for verdict, findings, required, message in cases:
            with self.subTest(verdict=verdict, findings=findings, required=required):
                with self.assertRaisesRegex(recorder.RecorderError, message):
                    recorder.record_review_state(
                        root=self.root,
                        issue=self.ISSUE,
                        verdict=verdict,
                        findings_count=findings,
                        required_changes_count=required,
                        reviewed_at=self.NOW,
                    )
                self.assertFalse(self._review_path().exists())

    def test_changes_requested_removes_only_current_branch_stale_approval(self):
        self._write_review(
            {
                "issueNumber": self.ISSUE,
                "branch": self.BRANCH,
                "headSha": "0" * 40,
                "verdict": "APPROVED",
                "reviewedAt": self.NOW.isoformat(),
                "qualityGateSha": "0" * 40,
                "requiredChangesCount": 0,
            }
        )
        other = self.root / ".codex" / "state" / "reviews" / "fix__999-other.json"
        other.write_text("{}", encoding="utf-8")

        result = recorder.record_review_state(
            root=self.root,
            issue=self.ISSUE,
            verdict="CHANGES_REQUESTED",
            findings_count=2,
            required_changes_count=2,
            reviewed_at=self.NOW,
        )

        self.assertIsNone(result)
        self.assertFalse(self._review_path().exists())
        self.assertTrue(other.exists())
        # 삭제할 stale 파일이 없어도 같은 명령은 안전하게 성공한다.
        recorder.record_review_state(
            root=self.root,
            issue=self.ISSUE,
            verdict="CHANGES_REQUESTED",
            findings_count=1,
            required_changes_count=1,
            reviewed_at=self.NOW,
        )

    def test_changes_requested_rejects_zero_or_inconsistent_counts(self):
        for findings, required in ((0, 1), (1, 0), (1, 2)):
            with self.subTest(findings=findings, required=required):
                with self.assertRaisesRegex(recorder.RecorderError, "finding"):
                    recorder.record_review_state(
                        root=self.root,
                        issue=self.ISSUE,
                        verdict="CHANGES_REQUESTED",
                        findings_count=findings,
                        required_changes_count=required,
                        reviewed_at=self.NOW,
                    )

    def test_atomic_write_failure_leaves_no_partial_or_temporary_file(self):
        with mock.patch.object(recorder.os, "replace", side_effect=OSError("synthetic failure")):
            with self.assertRaisesRegex(recorder.RecorderError, "원자적으로"):
                self._record_approved()

        self.assertFalse(self._review_path().exists())
        reviews = self._review_path().parent
        self.assertEqual([], list(reviews.glob(".review-state-*.tmp")))

    def test_atomic_replace_failure_preserves_existing_stale_approval(self):
        stale = {
            "issueNumber": self.ISSUE,
            "branch": self.BRANCH,
            "headSha": "0" * 40,
            "verdict": "APPROVED",
            "reviewedAt": "2026-08-11T00:00:00+00:00",
            "qualityGateSha": "0" * 40,
            "requiredChangesCount": 0,
        }
        self._write_review(stale)

        with mock.patch.object(recorder.os, "replace", side_effect=OSError("synthetic failure")):
            with self.assertRaisesRegex(recorder.RecorderError, "원자적으로"):
                self._record_approved()

        self.assertEqual(stale, json.loads(self._review_path().read_text(encoding="utf-8")))
        self.assertEqual([], list(self._review_path().parent.glob(".review-state-*.tmp")))

    def test_cli_has_no_force_root_or_output_path_bypass(self):
        parser = recorder.build_parser()
        for option in ("--force", "--root", "--output"):
            with self.subTest(option=option), self.assertRaises(SystemExit), redirect_stderr(io.StringIO()):
                parser.parse_args(
                    [
                        "--issue",
                        str(self.ISSUE),
                        "--verdict",
                        "APPROVED",
                        "--findings-count",
                        "0",
                        "--required-changes-count",
                        "0",
                        option,
                        "value",
                    ]
                )

    def _record_approved(self):
        return recorder.record_review_state(
            root=self.root,
            issue=self.ISSUE,
            verdict="APPROVED",
            findings_count=0,
            required_changes_count=0,
            reviewed_at=self.NOW,
        )

    def _git(self, *args: str) -> str:
        completed = subprocess.run(
            ["git", *args],
            cwd=self.root,
            text=True,
            capture_output=True,
            check=True,
        )
        return completed.stdout.strip()

    def _set_remote_head(self, sha: str):
        self._git("update-ref", f"refs/remotes/origin/{self.BRANCH}", sha)

    def _quality_path(self) -> Path:
        return self.root / ".codex" / "state" / "quality-gates" / "fix__126-review-approval-recorder.json"

    def _review_path(self) -> Path:
        return self.root / ".codex" / "state" / "reviews" / "fix__126-review-approval-recorder.json"

    def _write_quality_gate(self, *, sha: str | None = None):
        path = self._quality_path()
        if path.is_symlink():
            path.unlink()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(
                {
                    "branch": self.BRANCH,
                    "headSha": sha or self.sha,
                    "result": "SUCCESS",
                }
            ),
            encoding="utf-8",
        )

    def _write_review(self, payload: dict):
        path = self._review_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
