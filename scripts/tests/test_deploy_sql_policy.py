from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from deploy_sql_policy import find_violations  # noqa: E402


class DeploySqlPolicyTest(unittest.TestCase):
    def test_repository_deploy_sql_does_not_modify_supabase_owned_auth_objects(self):
        violations = find_violations(ROOT)

        self.assertEqual((), violations)

    def test_forbidden_patterns_are_detected_across_formatting_variants(self):
        sql = """
            CREATE SCHEMA IF NOT EXISTS auth;
            create table "auth" . "users" (id uuid);
            CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid AS $$ SELECT NULL $$ LANGUAGE sql;
            INSERT
              INTO ONLY auth.users(id) VALUES ('00000000-0000-0000-0000-000000000000');
        """

        violations = self._find_in_deploy_sql(sql)

        self.assertEqual(4, len(violations))

    def test_auth_references_and_comments_are_allowed(self):
        sql = """
            -- create table auth.users (id uuid);
            /* insert into auth.users values ('ignored'); */
            create table public.user_profiles (
              id uuid primary key references auth.users(id)
            );
            create policy owner_select on public.user_profiles
              using (id = (select auth.uid()));
        """

        violations = self._find_in_deploy_sql(sql)

        self.assertEqual((), violations)

    def test_explicit_local_postgres_compatibility_sql_is_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            local_sql = root / "db" / "local-postgres" / "auth.sql"
            local_sql.parent.mkdir(parents=True)
            local_sql.write_text("create table auth.users (id uuid);", encoding="utf-8")

            self.assertEqual((), find_violations(root))

    def _find_in_deploy_sql(self, sql: str):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            migration = root / "supabase" / "migrations" / "test.sql"
            migration.parent.mkdir(parents=True)
            migration.write_text(sql, encoding="utf-8")
            return find_violations(root)


if __name__ == "__main__":
    unittest.main()
