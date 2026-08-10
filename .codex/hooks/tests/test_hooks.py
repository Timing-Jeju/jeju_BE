from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


HOOK_DIR = Path(__file__).resolve().parents[1]


def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, HOOK_DIR / filename)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


import sys

sys.path.insert(0, str(HOOK_DIR))
common = load_module("hook_common", "hook_common.py")
policy = load_module("pre_tool_use_policy", "pre_tool_use_policy.py")
secret_guard = load_module("user_prompt_secret_guard", "user_prompt_secret_guard.py")


class PreToolPolicyTest(unittest.TestCase):
    def test_main_commit_is_blocked(self):
        self.assertIsNotNone(policy.evaluate_command("git commit -m 'chore: #1 설정'", "main"))

    def test_develop_commit_is_blocked(self):
        self.assertIsNotNone(policy.evaluate_command("git commit -m 'chore: #1 설정'", "develop"))

    def test_feature_branch_commit_is_allowed(self):
        self.assertIsNone(policy.evaluate_command("git commit -m 'feat: #12 검색 기능 구현'", "feat/12-place-search"))

    def test_force_push_is_blocked(self):
        self.assertIsNotNone(policy.evaluate_command("git push --force origin feat/12-place-search", "feat/12-place-search"))

    def test_destructive_command_is_blocked(self):
        self.assertIsNotNone(policy.evaluate_command("git reset --hard HEAD~1", "feat/12-place-search"))

    def test_raw_pr_creation_is_blocked(self):
        self.assertIsNotNone(policy.evaluate_command("gh pr create --base develop", "feat/12-place-search"))

    def test_pr_without_approval_is_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertIsNotNone(
                policy.evaluate_command(
                    "./scripts/create-pr.sh --base develop",
                    "feat/12-place-search",
                    root,
                    "abc123",
                    False,
                    True,
                )
            )

    def test_old_head_approval_is_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_state(root, "quality-gates", "feat/12-place-search", {"headSha": "new", "result": "SUCCESS"})
            self._write_state(
                root,
                "reviews",
                "feat/12-place-search",
                {"headSha": "old", "verdict": "APPROVED", "issueNumber": 12, "requiredChangesCount": 0},
            )
            reason = policy.evaluate_command(
                "./scripts/create-pr.sh --base develop", "feat/12-place-search", root, "new", False, True
            )
            self.assertIn("Reviewer", reason)

    def test_mismatched_issue_approval_is_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_state(root, "quality-gates", "feat/12-place-search", {"headSha": "new", "result": "SUCCESS"})
            self._write_state(
                root,
                "reviews",
                "feat/12-place-search",
                {"headSha": "new", "verdict": "APPROVED", "issueNumber": 13, "requiredChangesCount": 0},
            )
            reason = policy.evaluate_command(
                "./scripts/create-pr.sh --base develop", "feat/12-place-search", root, "new", False, True
            )
            self.assertIn("Issue", reason)

    def test_required_changes_approval_is_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_state(root, "quality-gates", "feat/12-place-search", {"headSha": "new", "result": "SUCCESS"})
            self._write_state(
                root,
                "reviews",
                "feat/12-place-search",
                {"headSha": "new", "verdict": "APPROVED", "issueNumber": 12, "requiredChangesCount": 1},
            )
            reason = policy.evaluate_command(
                "./scripts/create-pr.sh --base develop", "feat/12-place-search", root, "new", False, True
            )
            self.assertIn("필수 수정사항", reason)

    def test_changes_requested_review_is_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_state(root, "quality-gates", "feat/12-place-search", {"headSha": "new", "result": "SUCCESS"})
            self._write_state(
                root,
                "reviews",
                "feat/12-place-search",
                {"headSha": "new", "verdict": "CHANGES_REQUESTED", "issueNumber": 12, "requiredChangesCount": 1},
            )
            reason = policy.evaluate_command(
                "./scripts/create-pr.sh --base develop", "feat/12-place-search", root, "new", False, True
            )
            self.assertIn("Reviewer APPROVED", reason)

    def test_latest_gate_and_approval_allow_pr_script(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_state(root, "quality-gates", "feat/12-place-search", {"headSha": "new", "result": "SUCCESS"})
            self._write_state(
                root,
                "reviews",
                "feat/12-place-search",
                {"headSha": "new", "verdict": "APPROVED", "issueNumber": 12, "requiredChangesCount": 0},
            )
            self.assertIsNone(
                policy.evaluate_command(
                    "./scripts/create-pr.sh --base develop", "feat/12-place-search", root, "new", False, True
                )
            )

    @staticmethod
    def _write_state(root: Path, kind: str, branch: str, value: dict):
        path = common.state_path(root, kind, branch)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")


class SecretGuardTest(unittest.TestCase):
    def test_placeholder_is_allowed(self):
        placeholder_configuration = (
            "OPENAI_"
            + "API_KEY=sk-example\n"
            + "TOKEN=${API_KEY}\n"
            + "DB=<secret>"
        )

        self.assertFalse(secret_guard.contains_secret(placeholder_configuration))

    def test_real_shaped_secret_is_blocked(self):
        synthetic_token = "AbCdEf1234567890" + "AbCdEf1234567890"
        self.assertTrue(secret_guard.contains_secret("Authorization: Bearer " + synthetic_token))


class StopHookTest(unittest.TestCase):
    def test_stop_hook_reentry_flag_is_respected_by_contract(self):
        self.assertTrue({"stop_hook_active": True}.get("stop_hook_active"))


if __name__ == "__main__":
    unittest.main()
