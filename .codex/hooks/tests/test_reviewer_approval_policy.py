from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class ReviewerApprovalPolicyContractTest(unittest.TestCase):
    @staticmethod
    def _read(relative_path: str) -> str:
        return (ROOT / relative_path).read_text(encoding="utf-8")

    def test_global_policy_forbids_developer_and_pm_approval_state_changes(self):
        agents = self._read("AGENTS.md")

        self.assertIn("Developer와 PM은 승인 상태 파일을 생성·수정·삭제할 수 없다", agents)
        self.assertIn(".codex/state/reviews/{sanitized-branch}.json", agents)

    def test_global_policy_has_narrow_independent_reviewer_exception(self):
        agents = self._read("AGENTS.md")

        self.assertIn("timing-jeju-reviewer", agents)
        self.assertIn("finding 0건으로 APPROVED", agents)
        self.assertIn("현재 HEAD", agents)
        self.assertIn("CHANGES_REQUESTED", agents)

    def test_role_agent_instructions_match_approval_state_policy(self):
        developer = self._read(".codex/agents/developer.toml")
        pm = self._read(".codex/agents/pm.toml")
        reviewer = self._read(".codex/agents/reviewer.toml")

        self.assertIn("승인 상태 파일을 생성·수정·삭제하지 않는다", developer)
        self.assertIn("승인 상태 파일을 생성·수정·삭제하지 않는다", pm)
        self.assertIn("독립적으로 develop...HEAD", reviewer)
        self.assertIn("finding이 0건", reviewer)

    def test_reviewer_skill_defines_approved_and_changes_requested_state_actions(self):
        skill = self._read(".agents/skills/pre-pr-review/SKILL.md")

        self.assertIn("실제 timing-jeju-reviewer", skill)
        self.assertIn("finding이 0건", skill)
        self.assertIn("APPROVED 직후", skill)
        self.assertIn("CHANGES_REQUESTED이면 기존 stale 승인 상태 파일만 제거", skill)
        for field in (
            "issueNumber",
            "branch",
            "headSha",
            "verdict",
            "reviewedAt",
            "qualityGateSha",
            "requiredChangesCount",
        ):
            self.assertIn(field, skill)

    def test_code_review_document_matches_reviewer_state_lifecycle(self):
        code_review = self._read("docs/CODE_REVIEW.md")

        self.assertIn("Developer와 PM은 승인 상태 파일을 생성·수정·삭제할 수 없습니다", code_review)
        self.assertIn("finding 0건", code_review)
        self.assertIn("APPROVED 직후", code_review)
        self.assertIn("CHANGES_REQUESTED", code_review)
        self.assertIn("stale 승인 상태 파일", code_review)


if __name__ == "__main__":
    unittest.main()
