from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = (
    ROOT
    / "supabase"
    / "migrations"
    / "20260904000000_push_device_notification_preferences.sql"
)
SERVER_WRITER_BOUNDARY_MIGRATION = (
    ROOT
    / "supabase"
    / "migrations"
    / "20260904000001_push_notification_server_writer_boundary.sql"
)
SCHEMA_CONTRACT = ROOT / "db/queries/schema_contract.sql"
SMOKE_CHECK = ROOT / "db/queries/smoke_check.sql"
NEGATIVE_SQL = ROOT / "db/queries/database_negative_constraints.sql"
LOCAL_HELPER_SQL = ROOT / "db/local-postgres/supabase_smoke_fixture_helper.sql"
DOCKER_SMOKE = ROOT / "scripts/docker-smoke-test.sh"
FIXTURE_USER = "f1130000-0000-0000-0000-000000000001"


def compact(value: str) -> str:
    return re.sub(r"\s+", " ", value.lower()).strip()


class PushNotificationDatabaseTest(unittest.TestCase):
    def test_latest_migration_versions_are_unique_and_chronological(self):
        expected = (
            "20260901000000_legal_documents_consents.sql",
            "20260902000000_trip_create_contract.sql",
            "20260903000000_saved_places_api.sql",
            "20260904000000_push_device_notification_preferences.sql",
            "20260904000001_push_notification_server_writer_boundary.sql",
            "20260905000000_mcp_private_http_client.sql",
        )
        migration_names = tuple(
            path.name
            for path in sorted((ROOT / "supabase/migrations").glob("202609*.sql"))
        )
        versions = tuple(name[:14] for name in migration_names)

        self.assertEqual(expected, migration_names)
        self.assertEqual(len(versions), len(set(versions)))

    def test_docker_init_applies_trip_saved_push_correction_then_seed_exactly_once(self):
        mounts = (
            (
                "./supabase/migrations/20260902000000_trip_create_contract.sql",
                "/docker-entrypoint-initdb.d/031_trip_create_contract.sql",
            ),
            (
                "./supabase/migrations/20260903000000_saved_places_api.sql",
                "/docker-entrypoint-initdb.d/032_saved_places_api.sql",
            ),
            (
                "./supabase/migrations/20260904000000_push_device_notification_preferences.sql",
                "/docker-entrypoint-initdb.d/033_push_device_notification_preferences.sql",
            ),
            (
                "./supabase/migrations/20260904000001_push_notification_server_writer_boundary.sql",
                "/docker-entrypoint-initdb.d/034_push_notification_server_writer_boundary.sql",
            ),
            (
                "./supabase/migrations/20260905000000_mcp_private_http_client.sql",
                "/docker-entrypoint-initdb.d/035_mcp_private_http_client.sql",
            ),
            (
                "./db/local-postgres/seed_fixtures.sql",
                "/docker-entrypoint-initdb.d/099_seed_fixtures.sql",
            ),
        )

        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                positions = []
                for source, target in mounts:
                    exact_mount = f"{source}:{target}:ro"
                    self.assertEqual(1, compose.count(exact_mount), exact_mount)
                    positions.append(compose.index(exact_mount))
                self.assertEqual(sorted(positions), positions)

        docker_smoke = DOCKER_SMOKE.read_text(encoding="utf-8")
        migration_targets = tuple(target for _, target in mounts[:-1])
        for target in migration_targets:
            self.assertEqual(2, docker_smoke.count(target), target)
        for next_contract in (
            "/queries/legacy_v1_upgrade_contract.sql",
            "/queries/database_concurrency_contract.sql",
        ):
            with self.subTest(next_contract=next_contract):
                exact_sequence = " \\\n  ".join((*migration_targets, next_contract))
                self.assertEqual(
                    1,
                    docker_smoke.count(exact_sequence),
                    exact_sequence,
                )

    def test_server_writer_boundary_is_additive_and_removes_all_client_write_paths(self):
        self.assertTrue(SERVER_WRITER_BOUNDARY_MIGRATION.is_file())
        correction = compact(
            SERVER_WRITER_BOUNDARY_MIGRATION.read_text(encoding="utf-8")
        )

        for policy in (
            "push_devices_owner_insert",
            "push_devices_owner_update",
            "notification_preferences_owner_insert",
            "notification_preferences_owner_update",
        ):
            with self.subTest(policy=policy):
                self.assertIn(f"drop policy if exists {policy}", correction)

        for table in ("push_devices", "notification_preferences"):
            with self.subTest(table=table):
                self.assertIn(
                    f"revoke insert, update, delete on public.{table} from authenticated",
                    correction,
                )
                self.assertIn(
                    f"grant select, insert, update, delete on public.{table} to service_role",
                    correction,
                )

        self.assertNotRegex(
            correction,
            r"create\s+policy\s+\S+\s+on\s+public\.(?:push_devices|notification_preferences)\s+for\s+(?:insert|update|delete|all)",
        )
        schema_contract = compact(SCHEMA_CONTRACT.read_text(encoding="utf-8"))
        self.assertIn("owner_select_policy_count <> 2", schema_contract)
        self.assertIn("client_write_policy_count <> 0", schema_contract)
        self.assertIn(
            "has_table_privilege('authenticated', 'public.notification_preferences', 'insert')",
            schema_contract,
        )
        for privilege in ("select", "insert", "update", "delete"):
            self.assertIn(
                f"not has_table_privilege('service_role', 'public.push_devices', '{privilege}')",
                schema_contract,
            )

        smoke_check = compact(SMOKE_CHECK.read_text(encoding="utf-8"))
        self.assertIn(
            "and not ( grantee = 'authenticated' and table_name = 'notification_preferences' and privilege_type = 'select' )",
            smoke_check,
        )
        self.assertIn(
            "unexpected anon/authenticated table grants must not exist",
            smoke_check,
        )

    def test_deploy_negative_sql_creates_owner_through_local_helper_before_push_rows(self):
        negative = compact(NEGATIVE_SQL.read_text(encoding="utf-8"))
        helper = compact(LOCAL_HELPER_SQL.read_text(encoding="utf-8"))

        self.assertNotRegex(negative, r"insert\s+into\s+(?:public\.)?auth\.users")
        self.assertIn(
            "create function public.create_local_test_user(target_user_id uuid, target_email text)",
            helper,
        )
        fixture_call = (
            "select public.create_local_test_user( "
            f"'{FIXTURE_USER}', 'push-negative@issue113.test' );"
        )
        self.assertIn(fixture_call, negative)
        self.assertGreaterEqual(negative.count(FIXTURE_USER), 4)
        first_push_case = negative.index("push device fingerprint must be sha-256 length")
        self.assertLess(negative.index(fixture_call), first_push_case)
        for case in (
            "push device fingerprint must be sha-256 length",
            "active token fingerprint is globally unique",
            "notification safety buffer below inclusive range",
            "notification safety buffer above inclusive range",
        ):
            with self.subTest(case=case):
                self.assertIn(case, negative)
        self.assertTrue(negative.endswith("rollback;"))

    def test_tables_constraints_indexes_and_owner_rls_are_explicit(self):
        self.assertTrue(MIGRATION.is_file())
        sql = compact(MIGRATION.read_text(encoding="utf-8"))

        for fragment in (
            "create table public.push_devices",
            "unique (user_id, device_id)",
            "references auth.users(id) on delete cascade",
            "create unique index push_devices_active_token_fingerprint_key",
            "where invalidated_at is null",
            "create index push_devices_user_active_idx",
            "create table public.notification_preferences",
            "safety_buffer_minutes integer not null default 10",
            "safety_buffer_minutes between 0 and 120",
            "char_length(locale) between 2 and 35",
            "(?:-x(?:-[a-za-z0-9]{1,8})+)?$",
            "alter table public.push_devices enable row level security",
            "alter table public.notification_preferences enable row level security",
            "to authenticated using ( (select auth.uid()) = user_id )",
            "revoke all on public.push_devices from anon",
            "revoke all on public.notification_preferences from anon",
            "on public.push_devices to authenticated",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, sql)

        self.assertNotIn("security definer", sql)
        self.assertNotRegex(sql, r"grant\s+.+\s+to\s+(?:public|anon)")
        self.assertNotIn("grant select, insert, update on public.push_devices", sql)

    def test_token_columns_have_no_plaintext_surface_and_service_role_cannot_truncate(self):
        sql = compact(MIGRATION.read_text(encoding="utf-8"))
        self.assertIn("token_ciphertext text not null", sql)
        self.assertIn("token_fingerprint bytea not null", sql)
        self.assertNotRegex(sql, r"\bregistration_token\b")
        self.assertIn("revoke truncate on public.push_devices from service_role", sql)
        self.assertIn(
            "revoke truncate on public.notification_preferences from service_role", sql
        )


if __name__ == "__main__":
    unittest.main()
