import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260901000000_legal_documents_consents.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME
INTEGRATION_TEST = (
    ROOT
    / "services/spring-api/src/test/java/com/timingjeju/api/global/legal"
    / "JdbcLegalConsentStoreIntegrationTest.java"
)


class LegalConsentsContractTest(unittest.TestCase):
    def test_append_only_migration_is_mounted_before_fixtures_everywhere(self) -> None:
        self.assertTrue(MIGRATION.is_file())
        source = f"./supabase/migrations/{MIGRATION_NAME}"
        target = "/docker-entrypoint-initdb.d/030_legal_documents_consents.sql"
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            self.assertIn(source, compose)
            self.assertLess(compose.index(target), compose.index("/docker-entrypoint-initdb.d/099_seed_fixtures.sql"))

    def test_location_seed_matches_issue_73_exact_type_version_and_effective_date(self) -> None:
        migration = MIGRATION.read_text(encoding="utf-8")
        location = json.loads(
            (ROOT / "docs/contracts/domains/location-retention/contract.json").read_text(encoding="utf-8")
        )["consentPolicy"]
        self.assertEqual("location", location["documentType"])
        self.assertEqual("2026-08-11.v1", location["initialVersion"])
        self.assertIn("'location'", migration)
        self.assertIn("'2026-08-11.v1'", migration)
        self.assertIn("'2026-08-11T00:00:00+09:00'", migration)

    def test_schema_is_locale_versioned_server_only_and_collects_no_request_pii(self) -> None:
        migration = MIGRATION.read_text(encoding="utf-8").lower()
        self.assertIn("unique (document_type, locale, version)", migration)
        self.assertIn("revoke all on public.legal_documents from authenticated", migration)
        self.assertIn("revoke all on public.user_consents from authenticated", migration)
        for forbidden in ("ip_address", "latitude", "longitude", "raw_jwt", "access_token"):
            self.assertNotIn(forbidden, migration)
        self.assertNotIn("alter table auth.", migration)
        self.assertNotIn("insert into auth.", migration)

    def test_canonical_seed_conflicts_fail_closed_without_overwriting_or_deleting(self) -> None:
        migration = MIGRATION.read_text(encoding="utf-8").lower()
        self.assertIn("legal_document_seed_id_conflict", migration)
        self.assertIn("legal_document_seed_natural_key_conflict", migration)
        self.assertGreaterEqual(migration.count("errcode = '23505'"), 2)
        self.assertIn("on conflict (id) do nothing", migration)
        self.assertNotIn("on conflict (id) do update", migration)
        self.assertNotIn("delete from public.legal_documents", migration)

    def test_actual_postgresql_consent_clock_is_fixed_and_has_no_wall_clock_dependency(self) -> None:
        source = INTEGRATION_TEST.read_text(encoding="utf-8")
        self.assertIn("@Import(JdbcLegalConsentStoreIntegrationTest.FixedClockConfiguration.class)", source)
        self.assertIn("@Primary", source)
        self.assertIn("Clock.fixed(NOW, ZoneOffset.UTC)", source)
        self.assertIn("Timestamp.from(NOW.plusSeconds(86_400))", source)
        self.assertIn("Timestamp.from(NOW.minusSeconds(1))", source)
        self.assertNotIn("Instant.now()", source)
        self.assertNotIn("Clock.system", source)


if __name__ == "__main__":
    unittest.main()
