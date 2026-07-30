from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "supabase" / "migrations"
INTEGRITY_MIGRATION = MIGRATIONS / "20260730000000_database_integrity_hardening.sql"
INGESTION_MIGRATION = MIGRATIONS / "20260730010000_external_ingestion_foundation.sql"
CONSISTENCY_MIGRATION = MIGRATIONS / "20260730020000_ingestion_consistency_hardening.sql"
SCHEMA_CONTRACT = ROOT / "db" / "queries" / "schema_contract.sql"
NEGATIVE_CONTRACT = ROOT / "db" / "queries" / "database_negative_constraints.sql"


def compact_sql(contents: str) -> str:
    return re.sub(r"\s+", " ", contents.lower()).strip()


class DatabaseHardeningTest(unittest.TestCase):
    def read_migration(self, path: Path) -> str:
        self.assertTrue(path.is_file(), f"순차 migration이 없습니다: {path.name}")
        return compact_sql(path.read_text(encoding="utf-8"))

    def test_versioned_migrations_are_additive_and_ordered(self):
        migration_names = [path.name for path in sorted(MIGRATIONS.glob("*.sql"))]

        self.assertEqual(
            [
                "20260728000000_initial_public_schema.sql",
                "20260730000000_database_integrity_hardening.sql",
                "20260730010000_external_ingestion_foundation.sql",
                "20260730020000_ingestion_consistency_hardening.sql",
                "20260730030000_schedule_consistency_hardening.sql",
            ],
            migration_names,
        )

    def test_every_postgres_compose_mounts_all_migrations_before_fixture_seed(self):
        ordered_mounts = (
            "./db/local-postgres/auth_compat.sql",
            "./supabase/migrations/20260728000000_initial_public_schema.sql",
            "./supabase/migrations/20260730000000_database_integrity_hardening.sql",
            "./supabase/migrations/20260730010000_external_ingestion_foundation.sql",
            "./supabase/migrations/20260730020000_ingestion_consistency_hardening.sql",
            "./supabase/migrations/20260730030000_schedule_consistency_hardening.sql",
            "./db/local-postgres/seed_fixtures.sql",
        )

        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            contents = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                positions = [contents.find(mount) for mount in ordered_mounts]
                self.assertTrue(
                    all(position >= 0 for position in positions),
                    f"{compose_name}에 migration 또는 fixture mount가 누락됐습니다",
                )
                self.assertEqual(sorted(positions), positions)

    def test_both_database_smokes_execute_seed_free_schema_and_negative_contracts(self):
        docker_smoke = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )
        supabase_smoke = (ROOT / "scripts" / "supabase-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        for contract in (
            "/queries/schema_contract.sql",
            "/queries/database_negative_constraints.sql",
        ):
            with self.subTest(smoke="docker", contract=contract):
                self.assertIn(contract, docker_smoke)

        for contract in ("schema_contract.sql", "database_negative_constraints.sql"):
            with self.subTest(smoke="supabase", contract=contract):
                self.assertIn(contract, supabase_smoke)

    def test_import_run_state_machine_and_idempotency_are_database_constraints(self):
        migration = self.read_migration(INTEGRITY_MIGRATION)

        for column in (
            "parser_version",
            "schema_version",
            "idempotency_key",
            "parent_run_id",
            "checkpoint_before",
            "checkpoint_after",
            "retry_count",
            "fetched_count",
            "inserted_count",
            "updated_count",
            "skipped_count",
            "rejected_count",
            "deleted_count",
            "staled_count",
        ):
            with self.subTest(column=column):
                self.assertIn(column, migration)

        for status in ("running", "succeeded", "failed", "partial", "cancelled"):
            with self.subTest(status=status):
                self.assertRegex(migration, rf"\b{status}\b")

        self.assertIn("finished_at", migration)
        self.assertIn("error_code", migration)
        self.assertRegex(migration, r"retry_count\s*>?=\s*0")
        self.assertRegex(migration, r"inserted_count\s*>?=\s*0")
        self.assertRegex(migration, r"updated_count\s*>?=\s*0")
        self.assertRegex(migration, r"rejected_count\s*>?=\s*0")
        self.assertIn("create unique index", migration)
        self.assertIn("idempotency_key", migration)

    def test_external_ingestion_tables_have_versioned_raw_and_normalized_lineage(self):
        migration = self.read_migration(INGESTION_MIGRATION)
        new_tables = (
            "data_import_checkpoints",
            "external_api_snapshots",
            "tour_place_sources",
            "place_detail_items",
            "external_reference_codes",
        )
        for table in new_tables:
            with self.subTest(table=table):
                self.assertRegex(migration, rf"create table (?:public\.)?{table}\b")

        for column in (
            "import_run_id",
            "source_provider",
            "source_service",
            "source_operation",
            "scope_key",
            "request_hash",
            "page_key",
            "parser_version",
            "payload_hash",
            "raw_payload",
            "parse_status",
            "error_code",
            "error_message",
        ):
            with self.subTest(snapshot_column=column):
                self.assertIn(column, migration)

        for parse_status in ("received", "parsed", "rejected", "ignored", "tombstoned"):
            with self.subTest(parse_status=parse_status):
                self.assertRegex(migration, rf"\b{parse_status}\b")

        self.assertRegex(migration, r"payload_hash[^;]+(?:64|\{64\})")
        self.assertIn("source_snapshot_id", migration)
        self.assertIn("foreign key", migration)

    def test_raw_ingestion_tables_enable_rls_without_client_policies(self):
        migration = self.read_migration(INGESTION_MIGRATION)
        raw_tables = (
            "data_import_checkpoints",
            "external_api_snapshots",
            "tour_place_sources",
            "place_detail_items",
            "external_reference_codes",
        )

        for table in raw_tables:
            with self.subTest(table=table):
                self.assertRegex(
                    migration,
                    rf"alter table (?:public\.)?{table} enable row level security",
                )
                self.assertNotRegex(
                    migration,
                    rf"create policy [^;]+ on (?:public\.)?{table}\b",
                )

        self.assertNotRegex(migration, r"grant\s+[^;]+\s+to\s+(?:anon|authenticated)\b")
        self.assertIn("service_role", migration)

    def test_provider_scoped_keys_and_normalized_lineage_are_declared(self):
        migration = self.read_migration(INGESTION_MIGRATION)

        for table in ("tour_place_sources", "place_detail_items"):
            with self.subTest(table=table):
                self.assertRegex(migration, rf"{table}[^;]+source_provider")

        for table in (
            "place_details",
            "place_operating_hours",
            "place_aliases",
            "place_images",
            "route_stops",
            "weather_observations",
            "weather_forecasts",
            "bus_arrival_snapshots",
            "mobility_route_snapshots",
        ):
            with self.subTest(table=table):
                self.assertRegex(migration, rf"alter table (?:public\.)?{table}\b")

        self.assertRegex(
            migration,
            r"unique\s*\(\s*source_provider\s*,\s*source_service\s*,\s*external_id\s*\)",
        )
        self.assertRegex(
            migration,
            r"bus_stops[^;]+city_code",
        )
        self.assertRegex(
            migration,
            r"bus_routes[^;]+city_code",
        )
        self.assertIn("source_snapshot_id", migration)

    def test_hours_images_and_timetables_are_repeatable_and_lossless(self):
        migration = self.read_migration(INGESTION_MIGRATION)

        for column in ("interval_no", "valid_from", "valid_to"):
            with self.subTest(hours_column=column):
                self.assertIn(column, migration)

        for column in (
            "source_image_id",
            "copyright_code",
            "copyright_owner",
            "source_modified_at",
        ):
            with self.subTest(image_column=column):
                self.assertIn(column, migration)

        self.assertIn("source_record_key", migration)
        self.assertRegex(
            migration,
            r"foreign key\s*\(\s*route_id\s*,\s*direction_key\s*,\s*stop_id\s*\)"
            r"\s*references\s+(?:public\.)?route_stops",
        )
        self.assertRegex(migration, r"create unique index[^;]+place_images")
        self.assertRegex(migration, r"create unique index[^;]+timetable_entries")

    def test_latest_korservice2_codes_keep_legacy_columns_and_add_current_fields(self):
        foundation = self.read_migration(INGESTION_MIGRATION)
        consistency = self.read_migration(CONSISTENCY_MIGRATION)

        for legacy_column in (
            "area_code",
            "sigungu_code",
            "category_code_1",
            "category_code_2",
            "category_code_3",
        ):
            with self.subTest(legacy_column=legacy_column):
                self.assertIn(legacy_column, foundation)

        for current_column in (
            "l_dong_regn_cd",
            "l_dong_signgu_cd",
            "lcls_systm1",
            "lcls_systm2",
            "lcls_systm3",
        ):
            with self.subTest(current_column=current_column):
                self.assertRegex(
                    consistency,
                    rf"alter table (?:public\.)?tour_place_sources[^;]+{current_column}",
                )

        for source_field in (
            "ldongregncd",
            "ldongsigngucd",
            "lclssystm1",
            "lclssystm2",
            "lclssystm3",
        ):
            with self.subTest(source_field=source_field):
                self.assertIn(source_field, consistency)

    def test_ingestion_lineage_is_bound_to_provider_service_run_and_scope(self):
        migration = self.read_migration(CONSISTENCY_MIGRATION)

        for column in ("source_provider", "source_service"):
            with self.subTest(import_run_column=column):
                self.assertRegex(
                    migration,
                    rf"alter table (?:public\.)?data_import_runs[^;]+{column}",
                )

        self.assertRegex(
            migration,
            r"foreign key\s*\(\s*import_run_id\s*,\s*source_provider\s*,\s*source_service\s*,"
            r"\s*source_operation\s*,\s*scope_key\s*\)"
            r"\s*references\s+(?:public\.)?data_import_runs",
        )
        self.assertIn("validate_normalized_source_lineage", migration)
        self.assertIn("validate_checkpoint_succeeded_run", migration)
        self.assertIn("protect_external_snapshot_identity", migration)
        self.assertGreaterEqual(migration.count("for share"), 2)
        self.assertRegex(
            migration,
            r"snapshot_row\.parse_status\s+not in\s*\(\s*'parsed'\s*,\s*'tombstoned'\s*\)",
        )
        self.assertRegex(
            migration,
            r"alter column source_operation set not null",
        )
        self.assertIn("existing import run spans multiple snapshot source scopes", migration)
        self.assertIn("cannot return to an unparsed status", migration)
        self.assertRegex(migration, r"status\s*(?:<>|!=)\s*'succeeded'")
        for scope_column in (
            "source_provider",
            "source_service",
            "source_operation",
            "scope_key",
        ):
            with self.subTest(checkpoint_scope=scope_column):
                self.assertRegex(
                    migration,
                    rf"(?:old|new|checkpoint_row|run_row)\.{scope_column}",
                )

    def test_reference_timetable_and_hours_temporal_conflicts_are_blocked(self):
        migration = self.read_migration(CONSISTENCY_MIGRATION)

        self.assertRegex(
            migration,
            r"external_reference_codes[^;]+exclude using gist[^;]+daterange",
        )
        for column in ("source_service", "city_code"):
            with self.subTest(timetable_scope=column):
                self.assertRegex(
                    migration,
                    rf"alter table (?:public\.)?timetable_entries[^;]+{column}",
                )
        self.assertRegex(
            migration,
            r"timetable_entries[^;]+exclude using gist[^;]+source_provider[^;]+source_service"
            r"[^;]+city_code[^;]+source_record_key[^;]+daterange",
        )
        self.assertRegex(
            migration,
            r"route_stops[^;]+foreign key\s*\(\s*route_id\s*,\s*source_provider\s*,"
            r"\s*city_code\s*\).*bus_routes",
        )
        self.assertRegex(
            migration,
            r"route_stops[^;]+foreign key\s*\(\s*stop_id\s*,\s*source_provider\s*,"
            r"\s*city_code\s*\).*bus_stops",
        )
        self.assertRegex(
            migration,
            r"timetable_entries[^;]+foreign key\s*\(\s*route_id\s*,\s*direction_key\s*,"
            r"\s*stop_id\s*,\s*source_provider\s*,\s*city_code\s*\).*route_stops",
        )
        self.assertRegex(
            migration,
            r"place_operating_hours[^;]+last_entry_time[^;]+open_time[^;]+close_time",
        )
        self.assertRegex(
            migration,
            r"place_operating_hours[^;]+exclude using gist[^;]+is_closed\s+with\s+<>",
        )

    def test_schedule_day_coverage_and_content_move_guards_are_explicit(self):
        migration = self.read_migration(INTEGRITY_MIGRATION)

        self.assertRegex(
            migration,
            r"create (?:or replace )?function public\.assert_schedule_day_coverage\s*\(\s*"
            r"(?:target_schedule_version_id\s+)?uuid\s*,\s*"
            r"(?:target_trip_plan_id\s+)?uuid\s*\)",
        )
        self.assertRegex(
            migration,
            r"validate_schedule_version_sealing\(\).*?assert_schedule_version_sealable"
            r".*?assert_schedule_day_coverage",
        )
        self.assertRegex(
            migration,
            r"require_draft_schedule_version\(\).*?old\.schedule_version_id"
            r".*?new\.schedule_version_id",
        )
        self.assertRegex(
            migration,
            r"foreign key\s*\([^)]*trip_day_id[^)]*trip_plan_id[^)]*\)"
            r"\s*references\s+(?:public\.)?trip_days",
        )

    def test_database_contract_queries_are_portable_and_self_contained(self):
        schema_contract = SCHEMA_CONTRACT.read_text(encoding="utf-8").lower()
        negative_contract = NEGATIVE_CONTRACT.read_text(encoding="utf-8").lower()

        self.assertIn("pg_catalog", schema_contract)
        self.assertIn("pg_constraint", schema_contract)
        self.assertIn("pg_index", schema_contract)
        self.assertIn("relrowsecurity", schema_contract)
        self.assertIn("assert_schedule_day_coverage", schema_contract)
        self.assertIn("schema_contract", schema_contract)
        self.assertNotRegex(schema_contract, r"\b(insert|update|delete)\s+")
        self.assertNotIn("50000000-0000-0000-0000-000000000001", schema_contract)

        self.assertRegex(negative_contract, r"\bbegin\s*;")
        self.assertRegex(negative_contract, r"\brollback\s*;")
        self.assertIn("database_negative_constraints", negative_contract)
        self.assertIn("external_api_snapshots", negative_contract)
        self.assertIn("place_operating_hours", negative_contract)
        self.assertIn("timetable_entries", negative_contract)
        self.assertIn("assert_schedule_day_coverage", negative_contract)


if __name__ == "__main__":
    unittest.main()
