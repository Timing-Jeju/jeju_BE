from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260913000000_profile_image_storage.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME
COMPOSE_FILES = ("compose.yml", "compose.test.yml", "docker-compose.yml")
SLOT = "045_profile_image_storage.sql"
CANONICAL_KEY_PREDICATE = (
    "owner_id = (select auth.uid()::text) and name ~ (''^'' || "
    "(select auth.uid()::text) || "
    "''/profile/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'')"
)
EXPECTED_POLICIES = {
    "profile_images_owner_insert": (
        "on storage.objects as permissive for insert to authenticated with check ( "
        "bucket_id = ''profile-images'' and " + CANONICAL_KEY_PREDICATE + " )"
    ),
    "profile_images_owner_select": (
        "on storage.objects as permissive for select to authenticated using ( "
        "bucket_id = ''profile-images'' and " + CANONICAL_KEY_PREDICATE + " )"
    ),
    "profile_images_bucket_insert_guard": (
        "on storage.objects as restrictive for insert to authenticated with check "
        "(case when bucket_id <> ''profile-images'' then true else ( "
        + CANONICAL_KEY_PREDICATE
        + " ) end)"
    ),
    "profile_images_bucket_select_guard": (
        "on storage.objects as restrictive for select to authenticated using "
        "(case when bucket_id <> ''profile-images'' then true else ( "
        + CANONICAL_KEY_PREDICATE
        + " ) end)"
    ),
    "profile_images_bucket_update_guard": (
        "on storage.objects as restrictive for update to authenticated using "
        "(case when bucket_id <> ''profile-images'' then true else (false) end) "
        "with check (case when bucket_id <> ''profile-images'' then true else (false) end)"
    ),
    "profile_images_bucket_delete_guard": (
        "on storage.objects as restrictive for delete to authenticated using "
        "(case when bucket_id <> ''profile-images'' then true else (false) end)"
    ),
    "profile_images_anon_insert_guard": (
        "on storage.objects as restrictive for insert to anon with check "
        "(case when bucket_id <> ''profile-images'' then true else (false) end)"
    ),
    "profile_images_anon_select_guard": (
        "on storage.objects as restrictive for select to anon using "
        "(case when bucket_id <> ''profile-images'' then true else (false) end)"
    ),
}
EXPECTED_PRIVILEGES = {
    "revoke all on function public.sync_provider_profile_image_source() from public",
    "revoke all on function public.sync_provider_profile_image_source() from anon",
    "revoke all on function public.sync_provider_profile_image_source() from authenticated",
    "revoke all on function public.sync_provider_profile_image_source() from service_role",
    "revoke all on table public.profile_image_cleanup_outbox from public",
    "revoke all on table public.profile_image_cleanup_outbox from anon",
    "revoke all on table public.profile_image_cleanup_outbox from authenticated",
    "revoke all on table public.profile_image_cleanup_outbox from service_role",
    "grant select, insert, update on table public.profile_image_cleanup_outbox to service_role",
}


class ProfileImageStorageMigrationContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.sql = MIGRATION.read_text(encoding="utf-8").lower()

    def test_bucket_is_created_or_corrected_as_public_with_upload_limits(self) -> None:
        self.assertRegex(
            self.sql,
            re.compile(
                r"insert\s+into\s+storage\.buckets.*?"
                r"'profile-images'.*?'profile-images'.*?true.*?5242880.*?"
                r"image/jpeg.*?image/png.*?image/webp.*?"
                r"on\s+conflict\s*\(id\)\s+do\s+update.*?"
                r"public\s*=\s*excluded\.public.*?"
                r"file_size_limit\s*=\s*excluded\.file_size_limit.*?"
                r"allowed_mime_types\s*=\s*excluded\.allowed_mime_types",
                re.DOTALL,
            ),
        )

    def test_authenticated_owner_has_only_canonical_generation_insert(self) -> None:
        self._assert_storage_security_contract(self.sql)

    def test_restrictive_guards_block_profile_mutation_and_anon_db_access(self) -> None:
        self._assert_storage_security_contract(self.sql)

    def test_database_and_function_privileges_form_a_second_boundary(self) -> None:
        self.assertIn("alter table public.profile_image_cleanup_outbox enable row level security", self.sql)
        self._assert_storage_security_contract(self.sql)

    def test_storage_schema_uses_only_supported_bucket_configuration_and_rls_surface(self) -> None:
        self.assertNotRegex(
            self.sql,
            r"create\s+(?:or\s+replace\s+)?(?:function|table|index)\s+storage\.",
        )
        self.assertNotRegex(
            self.sql,
            r"(?:insert\s+into|update|delete\s+from)\s+storage\.objects",
        )
        self.assertNotIn("auth.role()", self.sql)
        self.assertNotIn("security definer", self.sql)
        self.assertIn("for insert to authenticated", self.sql)
        self.assertIn("for select to authenticated", self.sql)
        self.assertIn("as restrictive for insert to anon", self.sql)
        self.assertIn("as restrictive for select to anon", self.sql)

    def test_security_contract_kills_all_reviewer_mutations(self) -> None:
        mutations = (
            (
                "authenticated update bucket inversion",
                "profile_images_bucket_update_guard",
                "bucket_id <> ''profile-images''",
                "bucket_id = ''profile-images''",
            ),
            (
                "authenticated delete bucket inversion",
                "profile_images_bucket_delete_guard",
                "bucket_id <> ''profile-images''",
                "bucket_id = ''profile-images''",
            ),
            (
                "anon insert bucket inversion",
                "profile_images_anon_insert_guard",
                "bucket_id <> ''profile-images''",
                "bucket_id = ''profile-images''",
            ),
            (
                "anon select bucket inversion",
                "profile_images_anon_select_guard",
                "bucket_id <> ''profile-images''",
                "bucket_id = ''profile-images''",
            ),
            (
                "variable-width owner insert key",
                "profile_images_owner_insert",
                "[0-9a-f]{12}$",
                "[0-9a-f]+$",
            ),
        )
        for label, policy_name, original, replacement in mutations:
            with self.subTest(mutation=label):
                mutated = self._mutate_policy(policy_name, original, replacement)
                with self.assertRaises(AssertionError):
                    self._assert_storage_security_contract(mutated)

        service_revoke = (
            "execute 'revoke all on function "
            "public.sync_provider_profile_image_source() from service_role';"
        )
        self.assertIn(service_revoke, self.sql)
        with self.subTest(mutation="service role trigger function execute revoke removed"):
            with self.assertRaises(AssertionError):
                self._assert_storage_security_contract(self.sql.replace(service_revoke, "", 1))

    def test_compose_and_smoke_use_append_only_slot_045(self) -> None:
        mount = f"./supabase/migrations/{MIGRATION_NAME}:/docker-entrypoint-initdb.d/{SLOT}:ro"
        for relative_path in COMPOSE_FILES:
            with self.subTest(relative_path=relative_path):
                content = (ROOT / relative_path).read_text(encoding="utf-8")
                self.assertIn(mount, content)
                self.assertLess(content.index("044_trip_calendar_child_invariant_correction.sql"), content.index(SLOT))
                self.assertLess(content.index(SLOT), content.index("099_seed_fixtures.sql"))

        smoke = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(encoding="utf-8")
        self.assertEqual(2, smoke.count(f"/docker-entrypoint-initdb.d/{SLOT}"))

    def _assert_storage_security_contract(self, sql: str) -> None:
        policy_entries = [
            (match.group("name"), self._normalize(match.group("body")))
            for match in re.finditer(
                r"execute\s+'create policy (?P<name>profile_images_\w+)\b(?P<body>.*?)';",
                sql,
                re.DOTALL,
            )
        ]
        self.assertEqual(len(EXPECTED_POLICIES), len(policy_entries))
        self.assertEqual(EXPECTED_POLICIES, dict(policy_entries))

        privileges = [
            self._normalize(statement)
            for statement in re.findall(
                r"(?:execute\s+')?((?:revoke|grant)\s+[^;\n']+)(?:')?;",
                sql,
            )
        ]
        self.assertEqual(sorted(EXPECTED_PRIVILEGES), sorted(privileges))
        self.assertNotRegex(sql, r"(?:grant|revoke)\s+.*storage\.(?:objects|buckets)")

    def _mutate_policy(self, name: str, original: str, replacement: str) -> str:
        body = self._policy(name)
        self.assertGreater(body.count(original), 0, f"missing mutation target for {name}")
        return self.sql.replace(body, body.replace(original, replacement), 1)

    def _policy(self, name: str) -> str:
        match = re.search(
            rf"execute\s+'create policy {re.escape(name)}\b(?P<body>.*?)';",
            self.sql,
            re.DOTALL,
        )
        self.assertIsNotNone(match, f"missing policy {name}")
        return match.group("body")

    @staticmethod
    def _normalize(value: str) -> str:
        return re.sub(r"\s+", " ", value).strip()


if __name__ == "__main__":
    unittest.main()
