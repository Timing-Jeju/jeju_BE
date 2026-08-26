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


def compact(value: str) -> str:
    return re.sub(r"\s+", " ", value.lower()).strip()


class PushNotificationDatabaseTest(unittest.TestCase):
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
