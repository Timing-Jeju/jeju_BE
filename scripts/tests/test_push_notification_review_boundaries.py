from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JDBC = ROOT / "services/spring-api/src/main/java/com/timingjeju/api/global/notification/JdbcPushNotificationStore.java"
MIGRATION = ROOT / "supabase/migrations/20260904000000_push_device_notification_preferences.sql"
PORT = ROOT / "services/spring-api/src/main/java/com/timingjeju/api/application/notification/PushNotificationWithdrawalBoundary.java"
INTEGRATION = ROOT / "services/spring-api/src/test/java/com/timingjeju/api/global/notification/JdbcPushNotificationStoreIntegrationTest.java"


class PushNotificationReviewBoundariesTest(unittest.TestCase):
    def test_eligibility_uses_issue19_locale_fallback_semver_and_stable_tie_break(self):
        source = JDBC.read_text(encoding="utf-8")
        for fragment in (
            "LegalDocumentSelection.latest",
            "EFFECTIVE_LOCATION_CANDIDATES_SQL",
            "ACTIVE_PROFILE_LOCALE_SQL",
            "ELIGIBLE_FOR_DOCUMENT_SQL",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, source)
        self.assertNotIn("string_to_array(", source)
        self.assertNotIn("regexp_replace(ld.version", source)
        integration = INTEGRATION.read_text(encoding="utf-8")
        for fragment in (
            "eligibility_version선택은_issue19_Java_semantics와_exact일치한다",
            'List.of("1.0.0", "1.0")',
            'List.of("1.0-alpha", "1.0-beta")',
            "999999999999999999999999999999",
            'List.of("v1.0", "1.0")',
            'LegalDocumentSelection.latest(candidates, "en-US")',
            "consent(USER, loser, true, null)",
            "consent(USER, winner.documentId(), false, NOW)",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, integration)

    def test_eligibility_reads_one_repeatable_snapshot_and_documents_next_invocation(self):
        source = JDBC.read_text(encoding="utf-8")
        self.assertIn("import org.springframework.transaction.annotation.Isolation;", source)
        self.assertIn(
            "@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)", source
        )
        self.assertEqual(source.count("ACTIVE_PROFILE_LOCALE_SQL"), 2)
        self.assertEqual(source.count("EFFECTIVE_LOCATION_CANDIDATES_SQL"), 2)
        self.assertEqual(source.count("ELIGIBLE_FOR_DOCUMENT_SQL"), 2)

        contract = json.loads(
            (ROOT / "docs/contracts/domains/push-notifications/contract.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(contract["legalSelection"]["snapshotIsolation"], "REPEATABLE_READ")
        self.assertEqual(
            contract["legalSelection"]["concurrentCommitVisibility"], "next invocation"
        )

        architecture = (
            ROOT
            / "services/spring-api/src/test/java/com/timingjeju/api/architecture/ArchitectureTest.java"
        ).read_text(encoding="utf-8")
        self.assertIn("푸시_eligibility의_세조회는_repeatable_read_snapshot을_공유한다", architecture)
        self.assertIn("transaction.readOnly()).isTrue()", architecture)
        self.assertIn(
            "transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ)", architecture
        )

        integration = INTEGRATION.read_text(encoding="utf-8")
        for fragment in (
            "eligibility는_호출시작_snapshot을_유지하고_동시_최신required문서는_다음호출에_반영한다",
            "lock table public.legal_documents in access exclusive mode",
            "awaitLegalDocumentCandidateReadBlocked",
            "locationConsentDocumentId()).isEqualTo(LOCATION_DOCUMENT)",
            "eligibility.findEligible(USER, NOW)).isEmpty()",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, integration)

    def test_locale_policy_is_identical_in_contract_swagger_and_database(self):
        migration = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("char_length(locale) between 2 and 35", migration)
        pattern = "^[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-[A-Z]{2}|-[0-9]{3})?(?:-[A-Za-z0-9]{5,8}|-[0-9][A-Za-z0-9]{3})*(?:-[0-9A-WY-Za-wy-z](?:-[A-Za-z0-9]{2,8})+)*(?:-x(?:-[A-Za-z0-9]{1,8})+)?$"
        self.assertIn(f"locale ~ '{pattern}'", migration)
        contract = (ROOT / "docs/contracts/domains/push-notifications/contract.json").read_text(encoding="utf-8")
        self.assertIn(f'"pattern": "{pattern}"', contract)
        self.assertIn('"example": "en-US-u-ca-gregory"', contract)

    def test_withdrawal_boundary_is_additive_and_executable(self):
        self.assertTrue(PORT.is_file())
        port = PORT.read_text(encoding="utf-8")
        self.assertIn("onWithdrawalRequested(UUID userId, Instant requestedAt)", port)
        jdbc = JDBC.read_text(encoding="utf-8")
        self.assertIn("implements", jdbc)
        self.assertIn("PushNotificationWithdrawalBoundary", jdbc)
        self.assertIn("invalidated_at = coalesce(invalidated_at, ?::timestamptz)", jdbc)
        integration = INTEGRATION.read_text(encoding="utf-8")
        for fragment in (
            "탈퇴접수는_즉시_자기모든기기의_eligibility를_0으로_만들고_타사용자에_영향없다",
            "최종_auth삭제는_push기기와_preferences를_cascade하고_타사용자를_보존한다",
        ):
            self.assertIn(fragment, integration)


if __name__ == "__main__":
    unittest.main()
