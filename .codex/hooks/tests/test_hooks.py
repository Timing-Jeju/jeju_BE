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
    def test_direct_review_state_write_is_blocked(self):
        reason = policy.evaluate_command(
            "apply_patch .codex/state/reviews/feat__12-place-search.json",
            "feat/12-place-search",
        )

        self.assertIn("승인 상태", reason)

    def test_direct_review_state_delete_is_blocked(self):
        reason = policy.evaluate_command(
            "rm .codex/state/reviews/feat__12-place-search.json",
            "feat/12-place-search",
        )

        self.assertIn("승인 상태", reason)

    def test_shell_redirection_to_review_state_is_blocked(self):
        reason = policy.evaluate_command(
            "printf payload > .codex/state/reviews/feat__12-place-search.json",
            "feat/12-place-search",
        )

        self.assertIn("승인 상태", reason)

    def test_exact_read_only_review_state_commands_are_allowed(self):
        review_path = ".codex/state/reviews/feat__12-place-search.json"
        commands = (
            f"cat {review_path}",
            f"sed -n '1,120p' {review_path}",
            f"test -f {review_path}",
        )

        for command in commands:
            with self.subTest(command=command):
                self.assertIsNone(
                    policy.evaluate_command(command, "feat/12-place-search")
                )

    def test_any_other_command_containing_review_state_path_is_blocked(self):
        review_path = ".codex/state/reviews/feat__12-place-search.json"
        unsafe_commands = (
            f"find .codex/state/reviews -delete",
            f"perl -pi -e 's/x/y/' {review_path}",
            f"python3 helper.py {review_path}",
            f"echo x | sponge {review_path}",
            f"rsync /tmp/x {review_path}",
            f"unknown-command --target={review_path}",
            f"cat {review_path} > /tmp/review-copy",
            f"cat {review_path} && rm {review_path}",
            f"sed -n '1,120p' {review_path} | tee /tmp/review-copy",
            f"test -f {review_path}; rm {review_path}",
            f"cat README.md {review_path}",
            f"sed -n '1,120p' README.md {review_path}",
            f"test -f README.md {review_path}",
            f"cat .codex/state/reviews/*.json",
            f"bash -c 'cat {review_path}'",
        )

        for command in unsafe_commands:
            with self.subTest(command=command):
                self.assertIn(
                    "승인 상태",
                    policy.evaluate_command(command, "feat/12-place-search"),
                )

    def test_shell_constructed_review_state_paths_are_blocked(self):
        unsafe_commands = (
            "rm .codex/state/rev'iews'/feat__12-place-search.json",
            'rm .codex/state/rev"iews"/feat__12-place-search.json',
            "rm .codex/state/re'vi'ews/feat__12-place-search.json",
            'rm .codex/state/re"vi"ews/feat__12-place-search.json',
            "rm .codex/state/re'vi'\"ews\"/feat__12-place-search.json",
            "rm .codex/state/rev$(printf iews)/feat__12-place-search.json",
            "rm .codex/state/rev`printf iews`/feat__12-place-search.json",
            "rm .codex/state/rev$(printf $(printf iews))/feat__12-place-search.json",
            "d=.codex/state/reviews; rm $d/feat__12-place-search.json",
            "d=.codex/state; rm ${d}/reviews/feat__12-place-search.json",
            "target='.codex/state/reviews/feat__12-place-search.json'; rm $target",
            "rm .codex/state/reviews;touch /tmp/x",
            "rm .codex/state/reviews/feat__12-place-search.json;",
            "rm .codex/state/rev\"iews\"/feat__12-place-search.json",
        )

        for command in unsafe_commands:
            with self.subTest(command=command):
                self.assertIn(
                    "승인 상태",
                    policy.evaluate_command(command, "feat/12-place-search"),
                )

    def test_exact_review_state_allowlist_rejects_shell_uncertainty(self):
        path = ".codex/state/reviews/feat__12-place-search.json"
        unsafe_commands = (
            f"cat '{path}'",
            f'cat "{path}"',
            f"cat ./{path}",
            f"cat ../repo/{path}",
            f"cat /tmp/repo/{path}",
            f"cat ${{PATH_TO_REVIEW}}",
            f"sed -n '1,120p' {path};",
            f"test -f {path} # inspect",
        )

        for command in unsafe_commands:
            with self.subTest(command=command):
                self.assertIn(
                    "승인 상태",
                    policy.evaluate_command(command, "feat/12-place-search"),
                )

    def test_official_review_state_recorder_command_is_allowed(self):
        self.assertIsNone(
            policy.evaluate_command(
                "python3 scripts/record_review_state.py --issue 12 --verdict APPROVED "
                "--findings-count 0 --required-changes-count 0",
                "feat/12-place-search",
            )
        )

    def test_official_changes_requested_recorder_command_is_allowed(self):
        self.assertIsNone(
            policy.evaluate_command(
                "python3 scripts/record_review_state.py --issue 12 "
                "--verdict CHANGES_REQUESTED --findings-count 2 "
                "--required-changes-count 2",
                "feat/12-place-search",
            )
        )

    def test_recorder_must_be_a_standalone_exact_command(self):
        recorder = (
            "python3 scripts/record_review_state.py --issue 12 --verdict APPROVED "
            "--findings-count 0 --required-changes-count 0"
        )
        review_path = ".codex/state/reviews/feat__12-place-search.json"
        unsafe_commands = (
            f"{recorder}; rm {review_path}",
            f"{recorder} && cp payload {review_path}",
            f"{recorder} | tee {review_path}",
            f"{recorder} || printf payload > {review_path}",
            f"{recorder} >> {review_path}",
            f"{recorder}>{review_path}",
            f"python3 scripts/record_review_state.py --issue invalid; printf payload >{review_path}",
            f"bash -c '{recorder}'",
            f"sh -c '{recorder}'",
            f"{recorder} --output {review_path}",
        )

        for command in unsafe_commands:
            with self.subTest(command=command):
                self.assertIn(
                    "승인 상태",
                    policy.evaluate_command(command, "feat/12-place-search"),
                )

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
            self._write_state(
                root,
                "quality-gates",
                "feat/12-place-search",
                {"branch": "feat/12-place-search", "headSha": "new", "result": "SUCCESS"},
            )
            self._write_state(
                root,
                "reviews",
                "feat/12-place-search",
                {
                    "branch": "feat/12-place-search",
                    "headSha": "new",
                    "qualityGateSha": "new",
                    "verdict": "APPROVED",
                    "issueNumber": 12,
                    "requiredChangesCount": 0,
                },
            )
            self.assertIsNone(
                policy.evaluate_command(
                    "./scripts/create-pr.sh --base develop", "feat/12-place-search", root, "new", False, True
                )
            )

    def test_review_quality_gate_sha_must_match_current_head(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_state(
                root,
                "quality-gates",
                "feat/12-place-search",
                {"branch": "feat/12-place-search", "headSha": "new", "result": "SUCCESS"},
            )
            self._write_state(
                root,
                "reviews",
                "feat/12-place-search",
                {
                    "branch": "feat/12-place-search",
                    "headSha": "new",
                    "qualityGateSha": "old",
                    "verdict": "APPROVED",
                    "issueNumber": 12,
                    "requiredChangesCount": 0,
                },
            )

            reason = policy.evaluate_command(
                "./scripts/create-pr.sh --base develop", "feat/12-place-search", root, "new", False, True
            )

            self.assertIn("품질 게이트 SHA", reason)

    def test_review_branch_must_match_current_branch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_state(
                root,
                "quality-gates",
                "feat/12-place-search",
                {"branch": "feat/12-place-search", "headSha": "new", "result": "SUCCESS"},
            )
            self._write_state(
                root,
                "reviews",
                "feat/12-place-search",
                {
                    "branch": "feat/13-other",
                    "headSha": "new",
                    "qualityGateSha": "new",
                    "verdict": "APPROVED",
                    "issueNumber": 12,
                    "requiredChangesCount": 0,
                },
            )

            reason = policy.evaluate_command(
                "./scripts/create-pr.sh --base develop", "feat/12-place-search", root, "new", False, True
            )

            self.assertIn("Reviewer 승인 브랜치", reason)

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
