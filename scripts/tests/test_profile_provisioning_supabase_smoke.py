from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LOCAL_HELPER_FUNCTION = re.compile(
    r"create(?: or replace)? function public\.create_local_test_user\([^)]*\)"
    r".*?\$\$;",
    re.DOTALL,
)


def normalized_local_helper_definition(sql: str, *, compatibility: bool) -> str:
    match = LOCAL_HELPER_FUNCTION.search(sql.lower())
    if match is None:
        raise AssertionError("create_local_test_user 전체 정의가 필요합니다.")
    definition = match.group()
    if compatibility:
        definition = definition.replace(
            "create or replace function", "create function", 1
        )
    elif "create or replace function" in definition:
        raise AssertionError("Supabase smoke helper는 기존 managed Auth 함수를 덮어쓸 수 없습니다.")
    return " ".join(definition.split())


def assert_local_helper_definition_matches(helper: str, compatibility: str) -> None:
    if normalized_local_helper_definition(
        helper, compatibility=False
    ) != normalized_local_helper_definition(compatibility, compatibility=True):
        raise AssertionError("local Auth fixture helper 전체 정의가 auth_compat와 다릅니다.")


def assert_postgresql_driver_handoff(smoke: str) -> None:
    gradle_branches = re.findall(
        r'\(\s*cd "\$SPRING_DIR"(?P<body>.*?)'
        r"\./gradlew --no-daemon test --tests '\*SupabaseLocalAuthIntegrationTest'",
        smoke,
        re.DOTALL,
    )
    if len(gradle_branches) != 2:
        raise AssertionError("HS/JWKS Gradle 분기 두 개가 필요합니다.")
    driver = "SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver"
    url = "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:54322/postgres"
    for branch in gradle_branches:
        if branch.count(driver) != 1 or branch.count(url) != 1:
            raise AssertionError("각 Gradle 분기에 PostgreSQL URL/driver handoff가 필요합니다.")


def remove_occurrence(contents: str, needle: str, occurrence: int) -> str:
    start = -1
    for _ in range(occurrence):
        start = contents.find(needle, start + 1)
        if start < 0:
            raise AssertionError("mutation target을 찾지 못했습니다.")
    return contents[:start] + contents[start + len(needle) :]


class ProfileProvisioningSupabaseSmokeContractTest(unittest.TestCase):
    def test_local_auth_fixture_helper_is_bounded_and_matches_postgres_compatibility(self):
        helper_path = ROOT / "db/local-postgres/supabase_smoke_fixture_helper.sql"
        self.assertTrue(helper_path.is_file())
        helper = helper_path.read_text(encoding="utf-8").lower()
        compatibility = (ROOT / "db/local-postgres/auth_compat.sql").read_text(
            encoding="utf-8"
        ).lower()
        smoke = (ROOT / "scripts/supabase-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        assert_local_helper_definition_matches(helper, compatibility)
        for mutation in (
            helper.replace("target_email text", "target_email varchar", 1),
            helper.replace(
                "target_user_id uuid, target_email text",
                "target_email text, target_user_id uuid",
                1,
            ),
        ):
            with self.subTest(mutation=mutation):
                with self.assertRaisesRegex(AssertionError, "전체 정의"):
                    assert_local_helper_definition_matches(mutation, compatibility)
        self.assertNotIn("create or replace function", helper)
        self.assertRegex(
            helper,
            r"(?s)begin;\s*create function public\.create_local_test_user.*?"
            r"revoke execute on function public\.create_local_test_user\(uuid, text\) "
            r"from public;\s*"
            r"grant execute on function public\.create_local_test_user\(uuid, text\) "
            r"to supabase_admin;\s*commit;",
        )
        self.assertIn("security invoker", helper)
        self.assertIn("set search_path = ''", helper)
        self.assertIn("insert into auth.users", helper)
        self.assertNotIn("function auth.create_local_test_user", helper)
        self.assertNotIn("security definer", helper)
        self.assertNotRegex(helper, r"\bset\s+role\b")
        self.assertNotRegex(helper, r"\bgrant\b.*\bauth\b")
        revoke_public = (
            "revoke execute on function public.create_local_test_user(uuid, text) "
            "from public;"
        )
        self.assertIn(revoke_public, helper)
        self.assertIn(revoke_public, compatibility)
        self.assertIn(
            "grant execute on function public.create_local_test_user(uuid, text) "
            "to supabase_admin;",
            helper,
        )

        helper_reference = "db/local-postgres/supabase_smoke_fixture_helper.sql"
        install_marker = "[Supabase] local-only Auth fixture helper 설치"
        drop_marker = "drop function public.create_local_test_user(uuid, text);"
        auth_api_marker = "[Supabase] 로컬 Auth 명령 계약과 실제 access token 검증"
        self.assertIn(helper_reference, smoke)
        self.assertIn(install_marker, smoke)
        self.assertIn(drop_marker, smoke)
        self.assertLess(smoke.index(install_marker), smoke.index("[Supabase] 음수 무결성 계약 검사"))
        self.assertLess(smoke.index(drop_marker), smoke.index(auth_api_marker))
        self.assertIn("LOCAL_AUTH_FIXTURE_HELPER_INSTALLED=0", smoke)
        self.assertIn("LOCAL_AUTH_FIXTURE_HELPER_INSTALLED=1", smoke)
        cleanup = smoke.split("cleanup() {", 1)[1].split("\n}", 1)[0]
        cleanup_drop = "drop function if exists public.create_local_test_user(uuid, text);"
        self.assertIn(cleanup_drop, cleanup)
        self.assertLess(cleanup.index(cleanup_drop), cleanup.index('"$SUPABASE_BIN" stop'))
        install = smoke.index('< "$LOCAL_AUTH_FIXTURE_HELPER"')
        installed = smoke.index("LOCAL_AUTH_FIXTURE_HELPER_INSTALLED=1")
        self.assertLess(install, installed)
        normal_drop = smoke.index(drop_marker)
        normal_clear = smoke.index("LOCAL_AUTH_FIXTURE_HELPER_INSTALLED=0", normal_drop)
        self.assertLess(normal_drop, normal_clear)

        production_migrations = "\n".join(
            migration.read_text(encoding="utf-8").lower()
            for migration in (ROOT / "supabase/migrations").glob("*.sql")
        )
        self.assertNotIn("create_local_test_user", production_migrations)
        self.assertNotIn("insert into auth.users", production_migrations)

        negative_contract = (
            ROOT / "db/queries/database_negative_constraints.sql"
        ).read_text(encoding="utf-8").lower()
        concurrency_contract = (
            ROOT / "db/queries/database_concurrency_contract.sql"
        ).read_text(encoding="utf-8").lower()
        contracts = negative_contract + "\n" + concurrency_contract
        self.assertNotIn("auth.create_local_test_user", contracts)
        self.assertEqual(2, negative_contract.count("public.create_local_test_user"))
        self.assertEqual(1, concurrency_contract.count("public.create_local_test_user"))

    def test_canonical_bootstrap_checkpoints_are_exact_and_external_data_stays_empty(self):
        smoke = (ROOT / "scripts" / "supabase-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("CANONICAL_BOOTSTRAP_CHECKPOINT_STATE", smoke)
        self.assertIn('[ "$CANONICAL_BOOTSTRAP_CHECKPOINT_STATE" = "6|0" ]', smoke)
        canonical_migrations = {
            "supabase/migrations/20260818000000_tour_api_incremental_sync.sql": (
                "tour-api",
                "KorService2",
                "areaBasedSyncList2",
                "jeju",
                '"modifiedTime":"1970-01-01T00:00:00Z"',
            ),
            "supabase/migrations/20260819000000_tago_stop_import.sql": (
                "TAGO",
                "BusSttnInfoInqireService",
                "getSttnNoList",
                "jeju",
                '"cityCode":"unresolved"',
            ),
            "supabase/migrations/20260820000000_tago_route_stops_import.sql": (
                "TAGO",
                "BusRouteInfoInqireService",
                "getRouteNoList",
                "jeju-routes",
                '"routeCount":0,"routeStopCount":0',
            ),
            "supabase/migrations/20260824000000_tourapi_discovery_import_checkpoints.sql": (
                "tour-api",
                "KorService2",
                "locationBasedList2",
                "searchKeyword2",
                "searchStay2",
                "jeju",
                '"manifest":"uninitialized","pageCount":0',
            ),
        }
        for migration_path, canonical_fragments in canonical_migrations.items():
            migration = (ROOT / migration_path).read_text(encoding="utf-8")
            for canonical_fragment in canonical_fragments:
                with self.subTest(
                    migration=migration_path, canonical_fragment=canonical_fragment
                ):
                    self.assertIn(canonical_fragment, migration)
                    self.assertIn(canonical_fragment.replace('"', r'\"'), smoke)
        self.assertEqual(
            6, smoke.count("'1970-01-01T00:00:00Z'::timestamptz")
        )

        external_data_check = smoke.split("EXTERNAL_DATA_SEED_COUNT=$(", 1)[1].split(
            ")\n[", 1
        )[0]
        self.assertNotIn("data_import_checkpoints", external_data_check)
        self.assertIn("external_api_snapshots", external_data_check)
        self.assertIn("tour_place_sources", external_data_check)
        self.assertIn("place_detail_items", external_data_check)
        self.assertIn("external_reference_codes", external_data_check)
        self.assertIn("SEED_USER_DATA_COUNT", smoke)
        self.assertIn("public.user_profiles", smoke)
        self.assertIn("public.social_accounts", smoke)

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
        assert_postgresql_driver_handoff(smoke)
        driver = "SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver"
        self.assertEqual(2, smoke.count(driver))
        for occurrence in (1, 2):
            with self.subTest(missing_driver_branch=occurrence):
                with self.assertRaisesRegex(AssertionError, "driver handoff"):
                    assert_postgresql_driver_handoff(
                        remove_occurrence(smoke, driver, occurrence)
                    )
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
