from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = (
    ROOT
    / "supabase"
    / "migrations"
    / "20260902000000_push_device_notification_preferences.sql"
)
NEGATIVE_SQL = ROOT / "db/queries/database_negative_constraints.sql"
LOCAL_HELPER_SQL = ROOT / "db/local-postgres/supabase_smoke_fixture_helper.sql"
FIXTURE_USER = "f1130000-0000-0000-0000-000000000001"


def compact(value: str) -> str:
    return re.sub(r"\s+", " ", value.lower()).strip()


class PushNotificationDatabaseTest(unittest.TestCase):
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
            "with check ( (select auth.uid()) = user_id )",
            "revoke all on public.push_devices from anon",
            "revoke all on public.notification_preferences from anon",
            "on public.push_devices to authenticated",
            "grant select, insert, update on public.notification_preferences to authenticated",
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
