from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class ProfileProvisioningSupabaseSmokeContractTest(unittest.TestCase):
    def test_real_signup_token_invokes_profile_provisioning_against_local_supabase(self):
        smoke = (ROOT / "scripts" / "supabase-smoke-test.sh").read_text(
            encoding="utf-8"
        )
        test = (
            ROOT
            / "services/spring-api/src/test/java/com/timingjeju/api/global/security/"
            "SupabaseLocalAuthIntegrationTest.java"
        ).read_text(encoding="utf-8")

        self.assertIn("SUBJECT_FILE", smoke)
        self.assertIn("LOGIN_RESPONSE_FILE", smoke)
        self.assertIn("grant_type=password", smoke)
        self.assertIn("SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:54322/postgres", smoke)
        self.assertIn("PROFILE_PROVISION_COUNT", smoke)
        self.assertIn("SOCIAL_PROVISION_COUNT", smoke)
        self.assertIn("CurrentUserProvisioningService", test)
        self.assertIn('/api/v1/test/local-auth-profile', test)
        self.assertIn("service.provision(currentUserAccessor.getRequired())", test)

    def test_smoke_does_not_write_supabase_auth_tables_directly(self):
        smoke = (ROOT / "scripts" / "supabase-smoke-test.sh").read_text(
            encoding="utf-8"
        ).lower()
        java = (
            ROOT
            / "services/spring-api/src/test/java/com/timingjeju/api/global/security/"
            "SupabaseLocalAuthIntegrationTest.java"
        ).read_text(encoding="utf-8").lower()

        for forbidden in (
            "insert into auth.",
            "update auth.",
            "delete from auth.",
            "create table auth.",
            "alter table auth.",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, smoke)
                self.assertNotIn(forbidden, java)


if __name__ == "__main__":
    unittest.main()
