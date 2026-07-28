from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SUPABASE = ROOT / "supabase"
INITIAL_MIGRATION = SUPABASE / "migrations" / "20260728000000_initial_public_schema.sql"


EXPECTED_TABLES = {
    "ai_conversations",
    "ai_messages",
    "app_sessions",
    "bus_arrival_snapshots",
    "bus_routes",
    "bus_stops",
    "compute_runs",
    "data_import_runs",
    "itinerary_generation_candidates",
    "itinerary_generation_runs",
    "legal_documents",
    "live_state_snapshots",
    "mcp_compute_call_logs",
    "mobility_route_snapshots",
    "place_aliases",
    "place_details",
    "place_images",
    "place_operating_hours",
    "place_stop_links",
    "recommendation_candidates",
    "recovery_option_changes",
    "recovery_options",
    "risk_events",
    "route_stops",
    "saved_places",
    "social_accounts",
    "timetable_entries",
    "tour_places",
    "trip_accommodations",
    "trip_days",
    "trip_execution_events",
    "trip_item_progress",
    "trip_items",
    "trip_legs",
    "trip_place_preferences",
    "trip_plans",
    "trip_preferences",
    "trip_schedule_versions",
    "trip_transport_events",
    "trip_transport_modes",
    "trip_weather_impacts",
    "user_consents",
    "user_profiles",
    "weather_forecasts",
    "weather_grid_points",
    "weather_observations",
}


class SupabaseLayoutTest(unittest.TestCase):
    def test_cli_project_uses_versioned_migrations_and_empty_safe_seed(self):
        config = (SUPABASE / "config.toml").read_text(encoding="utf-8")
        seed = (SUPABASE / "seed.sql").read_text(encoding="utf-8")

        self.assertIn('project_id = "timing-jeju"', config)
        self.assertIn("[db.migrations]", config)
        self.assertIn("enabled = true", config)
        self.assertIn('sql_paths = ["./seed.sql"]', config)
        self.assertNotRegex(seed, r"(?i)\binsert\s+into\b")

    def test_initial_migration_preserves_public_schema_inventory(self):
        migration = INITIAL_MIGRATION.read_text(encoding="utf-8")
        tables = set(re.findall(r"(?im)^create table ([a-z_]+)", migration))

        self.assertEqual(EXPECTED_TABLES, tables)
        for extension in ("pgcrypto", "postgis", "btree_gist"):
            with self.subTest(extension=extension):
                self.assertRegex(
                    migration,
                    rf"(?im)^create extension if not exists {extension};$",
                )
        self.assertEqual(142, len(re.findall(r"(?im)^create (?:unique )?index ", migration)))
        self.assertEqual(11, len(re.findall(r"(?im)^create (?:or replace )?function ", migration)))
        self.assertEqual(12, len(re.findall(r"(?im)^create trigger ", migration)))
        self.assertEqual(26, len(re.findall(r"(?im)^create policy ", migration)))

    def test_general_postgres_compose_uses_local_auth_then_canonical_migration(self):
        for compose_name in ("compose.yml", "compose.test.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertIn("./db/local-postgres/auth_compat.sql", compose)
                self.assertIn(
                    "./supabase/migrations/20260728000000_initial_public_schema.sql",
                    compose,
                )
                self.assertIn("./db/local-postgres/seed_fixtures.sql", compose)
                self.assertNotIn("./db/init", compose)

    def test_docker_smoke_test_executes_postgis_schema_contract(self):
        compose = (ROOT / "compose.test.yml").read_text(encoding="utf-8")
        smoke_test = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("./db/queries:/queries:ro", compose)
        self.assertIn("psql", smoke_test)
        self.assertIn("/queries/smoke_check.sql", smoke_test)

    def test_flyway_is_not_added_as_a_second_migration_system(self):
        self.assertFalse((ROOT / "db" / "migration").exists())
        spring_files = (
            ROOT / "services" / "spring-api" / "build.gradle",
            ROOT / "services" / "spring-api" / "src/main/resources/application.yml",
        )
        for path in spring_files:
            with self.subTest(path=path):
                self.assertNotIn("flyway", path.read_text(encoding="utf-8").lower())

    def test_supabase_smoke_test_is_repeatable_and_always_cleans_up(self):
        smoke_test = (ROOT / "scripts" / "supabase-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn('SUPABASE_BIN=${SUPABASE_BIN:-supabase}', smoke_test)
        self.assertIn('DOCKER_BIN=${DOCKER_BIN:-docker}', smoke_test)
        self.assertIn('trap cleanup EXIT INT TERM', smoke_test)
        self.assertIn('"$SUPABASE_BIN" start', smoke_test)
        self.assertEqual(2, smoke_test.count('"$SUPABASE_BIN" db reset'))
        self.assertIn('"$SUPABASE_BIN" stop --no-backup', smoke_test)

    def test_common_quality_gate_runs_deploy_sql_policy_independently(self):
        quality_gate = (ROOT / "scripts" / "quality-gate.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("python3 scripts/deploy_sql_policy.py", quality_gate)

    def test_database_docs_explain_conservative_dynamic_execute_policy(self):
        database_docs = (ROOT / "db" / "README.md").read_text(encoding="utf-8")

        self.assertIn("`EXECUTE`", database_docs)
        self.assertIn("보수적", database_docs)
        self.assertIn("문자열 연결", database_docs)
        self.assertIn("`format(...)`", database_docs)
        self.assertIn("의미 분석", database_docs)
        self.assertIn("코드 리뷰", database_docs)


if __name__ == "__main__":
    unittest.main()
