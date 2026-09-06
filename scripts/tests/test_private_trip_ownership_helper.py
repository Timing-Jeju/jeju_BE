from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260917000000_private_trip_ownership_helper.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME
INIT_SLOT = "048_private_trip_ownership_helper.sql"
ACTUAL_PG_CONTRACT = ROOT / "db" / "queries" / "private_trip_ownership_helper_contract.sql"

EXPECTED_POLICIES = {
    "trip_preferences_owner_select": "trip_preferences",
    "trip_transport_modes_owner_select": "trip_transport_modes",
    "trip_transport_events_owner_select": "trip_transport_events",
    "trip_accommodations_owner_select": "trip_accommodations",
    "trip_days_owner_select": "trip_days",
    "trip_schedule_versions_owner_select": "trip_schedule_versions",
    "trip_items_owner_select": "trip_items",
    "itinerary_generation_runs_owner_select": "itinerary_generation_runs",
    "itinerary_generation_candidates_owner_select": "itinerary_generation_candidates",
    "trip_legs_owner_select": "trip_legs",
    "trip_item_progress_owner_select": "trip_item_progress",
    "trip_execution_events_owner_select": "trip_execution_events",
    "compute_runs_owner_select": "compute_runs",
    "risk_events_owner_select": "risk_events",
    "trip_weather_impacts_owner_select": "trip_weather_impacts",
    "recommendation_candidates_owner_select": "recommendation_candidates",
    "recovery_options_owner_select": "recovery_options",
    "recovery_option_changes_owner_select": "recovery_option_changes",
    "live_state_snapshots_owner_select": "live_state_snapshots",
}


def compact(source: str) -> str:
    return re.sub(r"\s+", " ", source.lower()).strip()


class PrivateTripOwnershipHelperContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.sql = compact(MIGRATION.read_text(encoding="utf-8"))

    def test_migration_is_atomic_additive_and_collision_free(self) -> None:
        self.assertEqual(MIGRATION.name, MIGRATION_NAME)
        timestamps = [path.name[:14] for path in (ROOT / "supabase/migrations").glob("*.sql")]
        self.assertEqual(len(timestamps), len(set(timestamps)))
        self.assertTrue(self.sql.startswith("begin;"))
        self.assertTrue(self.sql.endswith("commit;"))
        self.assertNotIn("alter table public.trip_plans disable row level security", self.sql)

    def test_helper_is_private_fail_closed_and_least_privileged(self) -> None:
        self._assert_target_security_contract(self.sql)

    def test_security_contract_kills_parent_and_auth_grant_widening(self) -> None:
        for mutation in (
            "grant select on table public.trip_plans to authenticated;",
            "grant usage on schema auth to authenticated;",
        ):
            with self.subTest(mutation=mutation):
                with self.assertRaises(AssertionError):
                    self._assert_target_security_contract(f"{self.sql} {mutation}")

    def _assert_target_security_contract(self, sql: str) -> None:
        self.assertIn(
            "create or replace function timing_jeju_private.owns_trip_plan( target_trip_plan_id uuid )",
            sql,
        )
        self.assertIn("language plpgsql stable security definer set search_path = ''", sql)
        self.assertIn("current_user_id := (select auth.uid())", sql)
        self.assertIn("when invalid_text_representation then return false", sql)
        self.assertIn("if current_user_id is null or target_trip_plan_id is null then return false", sql)
        self.assertIn("from public.trip_plans trip_plan", sql)
        body = sql.split("as $$", 1)[1].split("$$;", 1)[0]
        self.assertNotRegex(body, r"\bexecute\b|\braise\b|\binsert\b|\bupdate\b|\bdelete\b")

        for role in ("public", "anon", "authenticated", "service_role"):
            self.assertIn(f"revoke all on schema timing_jeju_private from {role}", sql)
            self.assertIn(
                "revoke all on function timing_jeju_private.owns_trip_plan(uuid) "
                f"from {role}",
                sql,
            )
        self.assertIn("grant usage on schema timing_jeju_private to authenticated", sql)
        self.assertIn(
            "grant execute on function timing_jeju_private.owns_trip_plan(uuid) to authenticated",
            sql,
        )
        self.assertNotIn("grant create on schema timing_jeju_private", sql)
        self.assertNotRegex(sql, r"grant\s+\w+(?:\s*,\s*\w+)*\s+on\s+table")
        self.assertNotRegex(sql, r"grant\s+usage\s+on\s+schema\s+auth\b")

    def test_exact_19_policy_inventory_uses_one_canonical_helper(self) -> None:
        creates = re.findall(r"create policy (\w+)", self.sql)
        self.assertCountEqual(creates, EXPECTED_POLICIES)
        self.assertEqual(len(creates), 19)
        for policy, table in EXPECTED_POLICIES.items():
            self.assertRegex(
                self.sql,
                rf"drop policy if exists {policy} on public\.{table}; create policy {policy} "
                rf"on public\.{table} for select to authenticated using \(",
            )
        self.assertEqual(self.sql.count("timing_jeju_private.owns_trip_plan("), 25)
        self.assertNotIn("create policy trip_plans_owner_select", self.sql)
        self.assertNotIn("drop policy if exists trip_plans_owner_select", self.sql)
        self.assertNotIn("trip_place_preferences_owner_select", self.sql)

    def test_candidates_take_direct_trip_plan_path_and_recovery_keeps_parent_path(self) -> None:
        candidate = self._policy("itinerary_generation_candidates_owner_select")
        self.assertIn("timing_jeju_private.owns_trip_plan(trip_plan_id)", candidate)
        self.assertNotIn("itinerary_generation_runs", candidate)

        recovery = self._policy("recovery_option_changes_owner_select")
        self.assertIn("from public.recovery_options recovery_option", recovery)
        self.assertIn("recovery_option.id = recovery_option_changes.recovery_option_id", recovery)
        self.assertIn(
            "timing_jeju_private.owns_trip_plan(recovery_option.trip_plan_id)", recovery
        )

    def test_legacy_helpers_are_dropped_without_cascade_after_policy_recreation(self) -> None:
        first_create = self.sql.index("create policy")
        private_drop = "drop function if exists timing_jeju_private.trip_preferences_owner(uuid);"
        public_drop = "drop function if exists public.owns_trip_plan(uuid);"
        self.assertGreater(self.sql.index(private_drop), first_create)
        self.assertGreater(self.sql.index(public_drop), first_create)
        self.assertNotRegex(self.sql, r"drop function[^;]+cascade")

    def test_private_schema_is_not_exposed_and_all_init_upgrade_paths_are_wired(self) -> None:
        config = compact((ROOT / "supabase/config.toml").read_text(encoding="utf-8"))
        self.assertNotIn("timing_jeju_private", config)
        mount = (
            f"./supabase/migrations/{MIGRATION_NAME}:"
            f"/docker-entrypoint-initdb.d/{INIT_SLOT}:ro"
        )
        seed = "./db/local-postgres/seed_fixtures.sql:/docker-entrypoint-initdb.d/099_seed_fixtures.sql:ro"
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            self.assertIn(mount, compose)
            self.assertLess(compose.index(mount), compose.index(seed))

        smoke = (ROOT / "scripts/docker-smoke-test.sh").read_text(encoding="utf-8")
        self.assertGreaterEqual(smoke.count(f"/docker-entrypoint-initdb.d/{INIT_SLOT}"), 2)
        self.assertIn("/queries/private_trip_ownership_helper_contract.sql", smoke)

    def test_actual_postgres_contract_covers_security_and_regression_matrix(self) -> None:
        source = compact(ACTUAL_PG_CONTRACT.read_text(encoding="utf-8"))
        required = (
            "owner -> other -> owner",
            "malformed jwt",
            "pg_temp shadow",
            "statement_timeout",
            "atomic rollback",
            "legacy dependency",
            "replay",
            "prosecdef",
            "provolatile",
            "proconfig",
            "rolsuper",
            "rolbypassrls",
            "recovery_option_changes",
            "itinerary_generation_candidates",
            "rollback",
        )
        for marker in required:
            self.assertIn(marker, source)
        for table in EXPECTED_POLICIES.values():
            self.assertIn(f"public.{table}", source)
        integration_source = compact(
            (
                ROOT
                / "services/spring-api/src/test/java/com/timingjeju/api/support/postgresql/PrivateTripOwnershipHelperMigrationIntegrationTest.java"
            ).read_text(encoding="utf-8")
        )
        self.assertIn("postgis/postgis:16-3.4", integration_source)
        self.assertIn("postgis/postgis:17-3.5", integration_source)
        self.assertIn("issue210_legacy_dependency", integration_source)
        self.assertIn("executescript(container, target())", integration_source)
        self.assertIn("seed_fixtures.sql", integration_source)
        self.assertIn("private_trip_ownership_helper_contract.sql", integration_source)
        self.assertIn("helper authorization mutation", integration_source)

    def test_actual_contract_proves_canonical_parent_acl_before_and_after_temp_grants(self) -> None:
        source = compact(ACTUAL_PG_CONTRACT.read_text(encoding="utf-8"))
        grant_start = source.index("grant select on table")
        rollback = source.index("rollback;")
        self.assertIn("canonical parent acl", source[:grant_start])
        self.assertIn("42501", source[:grant_start])
        self.assertIn("public.trip_preferences", source[:grant_start])
        self.assertIn("public.trip_transport_modes", source[:grant_start])
        self.assertNotIn("public.trip_plans,", source[grant_start:rollback])
        self.assertIn("post-rollback parent acl", source[rollback:])
        self.assertIn("42501", source[rollback:])

    def _policy(self, name: str) -> str:
        start = self.sql.index(f"create policy {name}")
        end = self.sql.index(";", start) + 1
        return self.sql[start:end]


if __name__ == "__main__":
    unittest.main()
