from __future__ import annotations

import hashlib
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260918000017_rls_auto_enable_execute_boundary.sql"
MIGRATION = ROOT / "supabase/migrations" / MIGRATION_NAME
AUTH_COMPAT = ROOT / "db/local-postgres/auth_compat.sql"
RELEASE_GUIDE = ROOT / "docs/SUPABASE_BOOTSTRAP_RELEASE.md"
PROFILE_IMAGE_INTEGRATION = ROOT / (
    "services/spring-api/src/test/java/com/timingjeju/api/support/postgresql/"
    "ProfileImageStoragePolicyMigrationIntegrationTest.java"
)
MOUNT = (
    f"./supabase/migrations/{MIGRATION_NAME}:"
    "/docker-entrypoint-initdb.d/055_rls_auto_enable_execute_boundary.sql:ro"
)


class RlsAutoEnableSecurityMigrationContractTest(unittest.TestCase):
    def test_append_only_migration_revokes_public_and_client_execute(self) -> None:
        sql = self._normalized_migration()

        self.assertIn(
            "revoke all on function public.rls_auto_enable() from public;", sql
        )
        self.assertIn(
            "revoke execute on function public.rls_auto_enable() from anon;", sql
        )
        self.assertIn(
            "revoke execute on function public.rls_auto_enable() from authenticated;", sql
        )
        self.assertNotRegex(sql, r"grant\s+execute\s+on\s+function")

    def test_migration_does_not_replace_function_or_event_trigger(self) -> None:
        sql = self._normalized_migration()

        self.assertNotRegex(sql, r"(?:create|drop|alter)\s+event\s+trigger")
        self.assertNotRegex(
            sql,
            r"(?:create|drop)\s+(?:or\s+replace\s+)?function\s+public\.rls_auto_enable",
        )

    def test_missing_expected_function_fails_closed_instead_of_skipping_revoke(self) -> None:
        sql = self._normalized_migration()

        self.assertNotIn("if exists", sql)
        self.assertNotIn("to_regprocedure", sql)
        self.assertNotIn("undefined_function", sql)
        self.assertFalse(sql.startswith("do $$"))

    def test_local_postgres_fixture_reproduces_supabase_event_trigger_boundary(self) -> None:
        sql = re.sub(r"\s+", " ", AUTH_COMPAT.read_text(encoding="utf-8").lower())

        self.assertIn(
            "create or replace function public.rls_auto_enable() returns event_trigger",
            sql,
        )
        self.assertIn("security definer set search_path = ''", sql)
        self.assertIn(
            "from pg_catalog.pg_event_trigger_ddl_commands() as ddl_command", sql
        )
        self.assertIn("ddl_command.schema_name = 'public'", sql)
        self.assertNotIn("where command.schema_name", sql)
        self.assertIn("alter table %s enable row level security", sql)
        self.assertIn("create event trigger rls_auto_enable", sql)
        self.assertIn("on ddl_command_end", sql)
        self.assertNotIn(
            "revoke execute on function public.rls_auto_enable()", sql
        )

    def test_actual_pg16_pg17_test_covers_acl_and_new_table_rls(self) -> None:
        source = (
            ROOT
            / "services/spring-api/src/test/java/com/timingjeju/api/support/postgresql/CanonicalMigrationOrderIntegrationTest.java"
        ).read_text(encoding="utf-8").lower()
        method = source.split(
            "void postgis_pg16과_pg17은_rlsautoenable_rpc권한을_회수하고_eventtrigger를_보존한다()",
            1,
        )[1].split("@test", 1)[0]

        for marker in (
            "has_function_privilege('anon','public.rls_auto_enable()','execute')",
            "has_function_privilege('authenticated','public.rls_auto_enable()','execute')",
            "create table public.issue242_rls_probe",
            "relrowsecurity",
            "pg_event_trigger",
            "postgresqltestcontainerfactory.createbefore(first_suffix, image)",
            "applycanonicalsuffix(container)",
            "show server_version_num",
            "expectedmajor",
        ):
            self.assertIn(marker, method)

    def test_release_guide_requires_project_recheck_and_records_security_checks(self) -> None:
        guide = RELEASE_GUIDE.read_text(encoding="utf-8").lower()

        for marker in (
            "issue #242",
            "live 적용 금지",
            "project ref",
            "'anon', 'public.rls_auto_enable()'",
            "'authenticated', 'public.rls_auto_enable()'",
            "pg_event_trigger",
            "relrowsecurity",
            "profile-images",
            "rollback",
            "reviewer 승인",
            "supabase db push --dry-run",
            "manifest exact ordered list",
            '(.immutableprefix + .canonicalsuffix)[] | .path | split("/")[-1]',
            "mismatch",
        ):
            self.assertIn(marker, guide)

    def test_storage_pg16_pg17_applies_slot_044_through_055_before_actual_rls_matrix(self) -> None:
        source = PROFILE_IMAGE_INTEGRATION.read_text(encoding="utf-8").lower()
        method = source.split(
            "void storage_do는_postgresql16과17에서_insertreturning_select와_불변정책을_보존한다()",
            1,
        )[1].split("private static", 1)[0]

        for marker in (
            'last = "20260918000017_rls_auto_enable_execute_boundary.sql"',
            "applycanonicalthroughsecurityboundary(versioncontainer)",
            "show server_version_num",
            "expectedmajor",
            "wrongownerkey",
            "invalidkey",
            "update storage.objects",
            "delete from storage.objects",
            '"anon"',
        ):
            self.assertIn(marker, source if marker.startswith("last =") else method)

    def test_manifest_registers_slot_055_sha_and_dependency(self) -> None:
        manifest = json.loads(
            (ROOT / "supabase/migrations/manifest.json").read_text(encoding="utf-8")
        )
        entry = manifest["canonicalSuffix"][-1]

        self.assertEqual(f"supabase/migrations/{MIGRATION_NAME}", entry["path"])
        self.assertEqual("055", entry["initSlot"])
        self.assertEqual({"issue": 242, "pullRequest": None}, entry["owner"])
        self.assertEqual(
            ["20260918000016_planned_route_reference_integrity.sql"],
            entry["dependencies"],
        )
        self.assertEqual(hashlib.sha256(MIGRATION.read_bytes()).hexdigest(), entry["sha256"])

    def test_compose_and_smoke_replay_include_slot_055_before_seed(self) -> None:
        for relative_path in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            with self.subTest(relative_path=relative_path):
                source = (ROOT / relative_path).read_text(encoding="utf-8")
                self.assertEqual(1, source.count(MOUNT))
                self.assertLess(
                    source.index("054_planned_route_reference_integrity.sql"),
                    source.index("055_rls_auto_enable_execute_boundary.sql"),
                )
                self.assertLess(
                    source.index("055_rls_auto_enable_execute_boundary.sql"),
                    source.index("099_seed_fixtures.sql"),
                )

        smoke = (ROOT / "scripts/docker-smoke-test.sh").read_text(encoding="utf-8")
        self.assertEqual(
            1,
            smoke.count(
                "/docker-entrypoint-initdb.d/055_rls_auto_enable_execute_boundary.sql"
            ),
        )

    @staticmethod
    def _normalized_migration() -> str:
        return re.sub(r"\s+", " ", MIGRATION.read_text(encoding="utf-8").lower()).strip()


if __name__ == "__main__":
    unittest.main()
