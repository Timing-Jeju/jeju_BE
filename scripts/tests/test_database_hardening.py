from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "supabase" / "migrations"
INTEGRITY_MIGRATION = MIGRATIONS / "20260730000000_database_integrity_hardening.sql"
INGESTION_MIGRATION = MIGRATIONS / "20260730010000_external_ingestion_foundation.sql"
CONSISTENCY_MIGRATION = MIGRATIONS / "20260730020000_ingestion_consistency_hardening.sql"
SCHEDULE_MIGRATION = MIGRATIONS / "20260730030000_schedule_consistency_hardening.sql"
SCHEMA_CONTRACT = ROOT / "db" / "queries" / "schema_contract.sql"
NEGATIVE_CONTRACT = ROOT / "db" / "queries" / "database_negative_constraints.sql"
LEGACY_UPGRADE_FIXTURE = ROOT / "db" / "queries" / "legacy_v1_upgrade_fixture.sql"
LEGACY_UPGRADE_CONTRACT = ROOT / "db" / "queries" / "legacy_v1_upgrade_contract.sql"
LEGACY_FOUNDATION_RUNNING_FIXTURE = (
    ROOT / "db" / "queries" / "legacy_foundation_running_scope_fixture.sql"
)
LEGACY_HOURS_CONFLICT_FIXTURE = (
    ROOT / "db" / "queries" / "legacy_v1_cross_day_conflict_fixture.sql"
)
LEGACY_RESULT_DAY_CONFLICT_FIXTURE = (
    ROOT / "db" / "queries" / "legacy_v1_result_day_conflict_fixture.sql"
)
LEGACY_RECOMMENDATION_DAY_CONFLICT_FIXTURE = (
    ROOT / "db" / "queries" / "legacy_v1_recommendation_day_conflict_fixture.sql"
)
LEGACY_BASE_LINEAGE_CONFLICT_FIXTURE = (
    ROOT / "db" / "queries" / "legacy_v1_base_lineage_conflict_fixture.sql"
)
LEGACY_FOUNDATION_CONFLICT_FIXTURES = (
    ROOT / "db" / "queries" / "legacy_foundation_external_reference_conflict_fixture.sql",
    ROOT / "db" / "queries" / "legacy_foundation_timetable_conflict_fixture.sql",
    ROOT / "db" / "queries" / "legacy_foundation_open_closed_conflict_fixture.sql",
    ROOT / "db" / "queries" / "legacy_foundation_multi_snapshot_scope_fixture.sql",
    ROOT / "db" / "queries" / "legacy_foundation_checkpoint_status_conflict_fixture.sql",
    ROOT / "db" / "queries" / "legacy_foundation_checkpoint_scope_conflict_fixture.sql",
    ROOT / "db" / "queries" / "legacy_foundation_unparsed_lineage_conflict_fixture.sql",
    ROOT / "db" / "queries" / "legacy_foundation_run_lineage_conflict_fixture.sql",
    ROOT / "db" / "queries" / "legacy_foundation_source_lineage_conflict_fixture.sql",
    ROOT / "db" / "queries" / "legacy_foundation_optional_lineage_conflict_fixture.sql",
)
CONCURRENCY_CONTRACT = ROOT / "db" / "queries" / "database_concurrency_contract.sql"


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

    def test_docker_smoke_replays_a_real_v1_to_latest_upgrade(self):
        docker_smoke = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        self.assertTrue(LEGACY_UPGRADE_FIXTURE.is_file())
        self.assertTrue(LEGACY_UPGRADE_CONTRACT.is_file())
        self.assertTrue(LEGACY_FOUNDATION_RUNNING_FIXTURE.is_file())
        self.assertTrue(LEGACY_HOURS_CONFLICT_FIXTURE.is_file())
        self.assertTrue(LEGACY_RESULT_DAY_CONFLICT_FIXTURE.is_file())
        self.assertTrue(LEGACY_BASE_LINEAGE_CONFLICT_FIXTURE.is_file())
        for fixture in LEGACY_FOUNDATION_CONFLICT_FIXTURES:
            with self.subTest(fixture=fixture.name):
                self.assertTrue(fixture.is_file())
        self.assertIn("timing_jeju_legacy_upgrade", docker_smoke)
        self.assertIn("timing_jeju_legacy_hours_conflict", docker_smoke)
        for path in (
            "/docker-entrypoint-initdb.d/001_auth_compat.sql",
            "/docker-entrypoint-initdb.d/002_application_schema.sql",
            "/queries/legacy_v1_upgrade_fixture.sql",
            "/docker-entrypoint-initdb.d/003_database_integrity_hardening.sql",
            "/docker-entrypoint-initdb.d/004_external_ingestion_foundation.sql",
            "/queries/legacy_foundation_running_scope_fixture.sql",
            "/docker-entrypoint-initdb.d/005_ingestion_consistency_hardening.sql",
            "/docker-entrypoint-initdb.d/006_schedule_consistency_hardening.sql",
            "/queries/legacy_v1_upgrade_contract.sql",
        ):
            with self.subTest(path=path):
                self.assertIn(path, docker_smoke)

        self.assertIn("/queries/legacy_v1_cross_day_conflict_fixture.sql", docker_smoke)
        self.assertIn("/queries/legacy_v1_result_day_conflict_fixture.sql", docker_smoke)
        self.assertIn(
            "/queries/legacy_v1_recommendation_day_conflict_fixture.sql",
            docker_smoke,
        )
        self.assertIn("/queries/legacy_v1_base_lineage_conflict_fixture.sql", docker_smoke)
        for fixture in LEGACY_FOUNDATION_CONFLICT_FIXTURES:
            with self.subTest(docker_fixture=fixture.name):
                self.assertIn(f"/queries/{fixture.name}", docker_smoke)
        self.assertIn(
            "legacy operating hours failed cross-day overlap audit", docker_smoke
        )
        self.assertIn(
            "legacy day-scoped result failed same-day lineage audit", docker_smoke
        )
        self.assertIn("legacy schedule base lineage is invalid", docker_smoke)
        self.assertIn("ed000000-0000-0000-0000-000000000070", docker_smoke)
        self.assertIn("ed000000-0000-0000-0000-000000000080", docker_smoke)
        self.assertIn("ee000000-0000-0000-0000-000000000021", docker_smoke)
        for audit_message in (
            "legacy external reference validity overlap audit failed",
            "legacy timetable validity overlap audit failed",
            "legacy operating hours open-closed overlap audit failed",
            "existing import run spans multiple snapshot source scopes",
            "legacy checkpoint succeeded-run audit failed",
        ):
            with self.subTest(audit=audit_message):
                self.assertIn(audit_message, docker_smoke)

        legacy_fixture = compact_sql(
            LEGACY_UPGRADE_FIXTURE.read_text(encoding="utf-8")
        )
        legacy_contract = compact_sql(
            LEGACY_UPGRADE_CONTRACT.read_text(encoding="utf-8")
        )
        self.assertIn("legacy_v1_oversized_values", legacy_fixture)
        self.assertIn("gen_random_bytes(1024)", legacy_fixture)
        self.assertIn("legacy-running-long-operation", legacy_fixture)
        self.assertIn("v1 oversized external identifiers were not preserved", legacy_contract)
        self.assertIn("v1 duplicate running scopes were not preserved safely", legacy_contract)
        self.assertIn("v1 oversized running import could not finish", legacy_contract)
        self.assertIn("v1 malformed running import could not finish", legacy_contract)
        self.assertIn("v1 oversized terminal import restarted as running", legacy_contract)
        self.assertIn("v1 malformed terminal import restarted as running", legacy_contract)
        self.assertIn("snapshot-linked duplicate running scopes were not grandfathered", legacy_contract)
        self.assertIn("legacy duplicate idempotency keys were not grandfathered", legacy_contract)
        self.assertIn("grandfathered idempotency on conflict arbiter was bypassed", legacy_contract)
        self.assertIn("canonical grandfathered idempotency arbiter was deleted", legacy_contract)
        self.assertIn("invalid legacy route stop accepted new payload lineage", legacy_contract)
        self.assertIn("invalid legacy timetable accepted new payload lineage", legacy_contract)
        self.assertIn("legacy null-day result lost its compute parent cascade", legacy_contract)

    def test_legacy_checkpoint_run_scope_is_audited_before_trigger_installation(self):
        consistency = self.read_migration(CONSISTENCY_MIGRATION)
        docker_smoke = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("legacy checkpoint succeeded-run audit failed", consistency)
        self.assertIn("checkpoint_id=%s, last_succeeded_run_id=%s", consistency)
        self.assertIn("run_status=%s", consistency)
        self.assertIn("checkpoint_scope=%s, run_scope=%s", consistency)
        self.assertRegex(
            consistency,
            r"checkpoint\.last_succeeded_run_id is not null[^;]+import_run\.status is distinct from 'succeeded'",
        )
        for scope_column in (
            "source_provider",
            "source_service",
            "source_operation",
            "scope_key",
        ):
            with self.subTest(scope_column=scope_column):
                self.assertIn(
                    f"import_run.{scope_column} is distinct from checkpoint.{scope_column}",
                    consistency,
                )

        self.assertIn(
            "/queries/legacy_foundation_checkpoint_status_conflict_fixture.sql",
            docker_smoke,
        )
        self.assertIn("timing_jeju_legacy_checkpoint_conflict", docker_smoke)

    def test_existing_nonnull_normalized_lineage_is_audited_after_write_guards(self):
        consistency = self.read_migration(CONSISTENCY_MIGRATION)
        docker_smoke = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        audit_position = consistency.index(
            "legacy normalized source lineage audit failed"
        )
        last_trigger_position = consistency.index(
            "create constraint trigger trg_mobility_routes_source_lineage"
        )
        self.assertGreater(audit_position, last_trigger_position)
        self.assertIn("table=%s, row_id=%s", consistency)
        self.assertIn("source_snapshot_id=%s", consistency)
        self.assertIn("normalized_run_id=%s, snapshot_run_id=%s", consistency)
        self.assertIn("parse_status=%s", consistency)
        self.assertIn("normalized_run_origin=%s/%s", consistency)
        self.assertIn("snapshot_run_origin=%s/%s", consistency)
        self.assertRegex(
            consistency,
            r"normalized_lineage_is_optional\s*\([^;]+"
            r"snapshot_import_run\.source_kind[^;]+"
            r"normalized_import_run\.source_kind",
        )
        for table_name in (
            "tour_places",
            "tour_place_sources",
            "place_details",
            "place_detail_items",
            "place_operating_hours",
            "place_aliases",
            "place_images",
            "external_reference_codes",
            "bus_stops",
            "bus_routes",
            "route_stops",
            "timetable_entries",
            "weather_observations",
            "weather_forecasts",
            "bus_arrival_snapshots",
            "mobility_route_snapshots",
        ):
            with self.subTest(table_name=table_name):
                self.assertIn(f"'{table_name}'", consistency)

        for fixture_name in (
            "legacy_foundation_unparsed_lineage_conflict_fixture.sql",
            "legacy_foundation_run_lineage_conflict_fixture.sql",
            "legacy_foundation_source_lineage_conflict_fixture.sql",
            "legacy_foundation_optional_lineage_conflict_fixture.sql",
        ):
            with self.subTest(fixture_name=fixture_name):
                self.assertIn(f"/queries/{fixture_name}", docker_smoke)

    def test_legacy_text_keys_are_grandfathered_without_raw_wide_indexes(self):
        foundation = self.read_migration(INGESTION_MIGRATION)
        consistency = self.read_migration(CONSISTENCY_MIGRATION)

        self.assertNotIn("idx_place_images_provider_url_transition", foundation)
        for constraint_name in (
            "ck_place_images_source_key_lengths",
            "ck_bus_stops_source_key_lengths",
            "ck_bus_routes_source_key_lengths",
            "ck_timetable_source_key_lengths",
            "ck_mobility_source_key_lengths",
        ):
            with self.subTest(length_constraint=constraint_name):
                self.assertRegex(
                    foundation + " " + consistency,
                    rf"constraint {constraint_name}[^;]+octet_length[^;]+not valid",
                )

        self.assertNotIn("ck_data_import_runs_source_key_lengths", consistency)
        self.assertIn("validate_import_run_source_key_lengths", consistency)
        self.assertIn("trg_data_import_runs_source_key_insert", consistency)
        self.assertIn("trg_data_import_runs_source_key_update", consistency)

        for index_name in (
            "uq_bus_stops_provider_city_node",
            "uq_bus_routes_provider_city_external",
            "uq_timetable_source_scope_record_validity",
            "ex_timetable_source_scope_no_validity_overlap",
        ):
            with self.subTest(bounded_index=index_name):
                self.assertRegex(
                    consistency if index_name.startswith(("uq_timetable_source", "ex_")) else foundation,
                    rf"{index_name}[^;]+where[^;]+octet_length",
                )

        self.assertIn("source_identity_digest", consistency)
        self.assertIn("place image source digest collision", consistency)
        self.assertNotRegex(
            consistency,
            r"foreign key\s*\([^)]*source_provider[^)]*\)\s*references",
        )
        for trigger_function in (
            "validate_external_snapshot_import_scope",
            "validate_route_stop_source_scope",
            "validate_timetable_source_scope",
        ):
            self.assertIn(trigger_function, consistency)
        self.assertRegex(
            consistency,
            r"trigger trg_route_stops_validate_source_scope before insert or update on",
        )
        self.assertIn("legacy timetable source identity is immutable", consistency)

    def test_legacy_null_day_results_keep_original_parent_foreign_keys(self):
        migration = self.read_migration(SCHEDULE_MIGRATION)

        self.assertNotIn("confrelid in", migration)
        self.assertIn("fk_trip_weather_impacts_compute_day", migration)
        self.assertIn("fk_recommendation_candidates_compute_day", migration)

    def test_docker_smoke_runs_isolated_two_session_concurrency_contracts(self):
        docker_smoke = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        self.assertTrue(CONCURRENCY_CONTRACT.is_file())
        concurrency_contract = compact_sql(
            CONCURRENCY_CONTRACT.read_text(encoding="utf-8")
        )

        self.assertIn("timing_jeju_concurrency", docker_smoke)
        self.assertIn("/queries/database_concurrency_contract.sql", docker_smoke)
        for path in (
            "/docker-entrypoint-initdb.d/001_auth_compat.sql",
            "/docker-entrypoint-initdb.d/002_application_schema.sql",
            "/docker-entrypoint-initdb.d/003_database_integrity_hardening.sql",
            "/docker-entrypoint-initdb.d/004_external_ingestion_foundation.sql",
            "/docker-entrypoint-initdb.d/005_ingestion_consistency_hardening.sql",
            "/docker-entrypoint-initdb.d/006_schedule_consistency_hardening.sql",
        ):
            with self.subTest(path=path):
                self.assertIn(path, docker_smoke)

        self.assertIn("create extension if not exists dblink", concurrency_contract)
        self.assertIn("dblink_send_query", concurrency_contract)
        self.assertIn("pg_blocking_pids", concurrency_contract)
        self.assertIn("advance_data_import_checkpoint", concurrency_contract)
        self.assertIn("40001", concurrency_contract)
        self.assertIn("p0001", concurrency_contract)
        self.assertIn("database_concurrency_contract", concurrency_contract)

        for migration in MIGRATIONS.glob("*.sql"):
            with self.subTest(migration=migration.name):
                self.assertNotIn(
                    "dblink",
                    migration.read_text(encoding="utf-8").lower(),
                    "dblink는 삭제되는 Docker 동시성 검사 DB에만 설치해야 합니다",
                )

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

        self.assertNotRegex(
            migration,
            r"foreign key\s*\(\s*import_run_id\s*,\s*source_provider",
        )
        self.assertIn("protect_import_run_source_scope", migration)
        self.assertIn("validate_external_snapshot_import_scope", migration)
        self.assertIn("for key share", migration)
        self.assertIn("validate_normalized_source_lineage", migration)
        self.assertIn("validate_checkpoint_succeeded_run", migration)
        self.assertIn("protect_checkpoint_succeeded_run", migration)
        self.assertIn("protect_checkpoint_progress", migration)
        self.assertIn("advance_data_import_checkpoint", migration)
        self.assertIn("checkpoint update requires compare-and-set version increment", migration)
        self.assertRegex(
            migration,
            r"update public\.data_import_checkpoints[^;]+version\s*=\s*p_expected_version",
        )
        self.assertRegex(
            migration,
            r"revoke update, delete, truncate on public\.data_import_checkpoints from service_role",
        )
        self.assertIn("prevent_checkpoint_reset", migration)
        self.assertIn("checkpoint cannot move to an older succeeded import run", migration)
        self.assertLess(
            migration.index("create constraint trigger trg_data_import_runs_protect_checkpoint"),
            migration.index("legacy checkpoint succeeded-run audit failed"),
        )
        self.assertIn("protect_external_snapshot_identity", migration)
        self.assertIn("external snapshot audit payload is immutable", migration)
        self.assertGreaterEqual(
            migration.count("for share") + migration.count("for key share"),
            3,
        )
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

        self.assertIn("normalized external source row requires a source snapshot", migration)
        self.assertRegex(migration, r"tg_op\s*=\s*'insert'")
        self.assertIn("legacy lineage-free row content is immutable", migration)
        self.assertIn("legacy lineage-free row content is immutable", migration)
        self.assertRegex(
            migration,
            r"normalized_row\s*-\s*array\s*\[\s*'updated_at'\s*\]",
        )
        self.assertIn("snapshot purge may clear only the source pointer", migration)
        self.assertRegex(
            migration,
            r"old_normalized_row\s*->>\s*'source_snapshot_id'[^;]+not exists\s*\([^;]+external_api_snapshots",
        )
        self.assertNotRegex(
            migration,
            r"lineage_optional\s*:=\s*tg_table_name\s*=\s*'tour_places'\s+or",
        )
        self.assertIn("normalized_lineage_is_optional", migration)
        self.assertRegex(
            migration,
            r"old_lineage_optional\s+and\s+lineage_optional",
        )
        self.assertIn("old_origin_is_external", migration)
        self.assertRegex(
            migration,
            r"old_origin_is_external\s*:=.*?"
            r"(?:source_kind|run_source_kind).*?"
            r"(?:source_provider|run_source_provider)",
        )
        self.assertIn(
            "external normalized row cannot become an optional lineage row",
            migration,
        )

        negative_contract = NEGATIVE_CONTRACT.read_text(encoding="utf-8").lower()
        self.assertIn("new external tour place requires source snapshot lineage", negative_contract)
        self.assertIn("snapshot retention purge preserves import run lineage", negative_contract)
        self.assertIn("checkpoint rows cannot be deleted to reset progress", negative_contract)
        self.assertIn("checkpoint table cannot be truncated to reset progress", negative_contract)
        self.assertIn("external-looking row cannot borrow an admin import run", negative_contract)
        self.assertIn("admin marker cannot borrow a tour api import run", negative_contract)
        self.assertIn("optional row scope must match its fixture import run", negative_contract)
        self.assertIn(
            "retained external row cannot borrow an optional import run",
            negative_contract,
        )
        self.assertIn(
            "snapshot-backed external row cannot become optional without lineage",
            negative_contract,
        )
        self.assertIn(
            "snapshot-backed user-query alias cannot clear a live snapshot pointer",
            negative_contract,
        )
        self.assertIn(
            "snapshot-backed user-query alias cannot remove external lineage",
            negative_contract,
        )
        self.assertIn(
            "snapshot-backed reserved provider cannot remove external lineage",
            negative_contract,
        )
        self.assertIn("admin exception row remains editable", negative_contract)
        self.assertIn("fixture exception row remains editable", negative_contract)
        self.assertIn("manual exception row remains editable", negative_contract)

        legacy_contract = LEGACY_UPGRADE_CONTRACT.read_text(encoding="utf-8").lower()
        self.assertIn("legacy external row cannot become an optional lineage row", legacy_contract)
        self.assertIn("legacy external row cannot borrow an optional import run", legacy_contract)

    def test_schema_contract_enforces_checkpoint_rpc_and_legacy_constraints(self):
        schema_contract = compact_sql(SCHEMA_CONTRACT.read_text(encoding="utf-8"))

        self.assertIn("source_url_key", schema_contract)
        self.assertIn("has_function_privilege", schema_contract)
        for role in ("anon", "authenticated", "service_role"):
            with self.subTest(role=role):
                self.assertRegex(
                    schema_contract,
                    rf"has_function_privilege\s*\(\s*'{role}'[^;]+execute",
                )
        for privilege in ("update", "delete", "truncate"):
            with self.subTest(privilege=privilege):
                self.assertRegex(
                    schema_contract,
                    rf"has_table_privilege\s*\(\s*'service_role'[^;]+{privilege}",
                )

        for constraint_name in (
            "ck_route_stops_source_provider_nonblank",
            "ck_mobility_request_hash_nonblank",
            "ck_place_images_image_url_nonblank",
            "ck_bus_stops_node_id_nonblank",
        ):
            with self.subTest(constraint=constraint_name):
                self.assertIn(constraint_name, schema_contract)

    def test_import_idempotency_uses_canonical_provider_service_scope(self):
        migration = self.read_migration(CONSISTENCY_MIGRATION)
        negative_contract = NEGATIVE_CONTRACT.read_text(encoding="utf-8").lower()

        self.assertRegex(
            migration,
            r"create unique index uq_data_import_runs_idempotency[^;]+"
            r"source_provider[^;]+source_service[^;]+source_operation[^;]+scope_key"
            r"[^;]+idempotency_key",
        )
        self.assertRegex(
            migration,
            r"create unique index uq_data_import_runs_running_scope[^;]+"
            r"source_provider[^;]+source_service[^;]+source_operation[^;]+scope_key",
        )
        self.assertIn("idempotency_enforced", migration)
        self.assertIn("running_scope_enforced", migration)
        self.assertIn("protect_import_run_idempotency", migration)
        self.assertIn("protect_import_run_running_scope", migration)
        self.assertIn("protect_grandfathered_idempotency_arbiter", migration)
        self.assertIn("assigned import idempotency key cannot be cleared", negative_contract)
        self.assertIn("new running import cannot opt out of scope enforcement", negative_contract)
        self.assertIn("same provider service ignores source name for idempotency", negative_contract)
        self.assertIn("different provider service may reuse an idempotency key", negative_contract)

    def test_legacy_rows_are_preserved_while_new_rows_use_strict_constraints(self):
        integrity = self.read_migration(INTEGRITY_MIGRATION)
        foundation = self.read_migration(INGESTION_MIGRATION)
        consistency = self.read_migration(CONSISTENCY_MIGRATION)
        schedule = self.read_migration(SCHEDULE_MIGRATION)

        for constraint_name in (
            "chk_data_import_runs_nonblank_fields",
            "chk_data_import_runs_json_objects",
            "chk_data_import_runs_error_pair",
            "chk_data_import_runs_state_fields",
            "chk_data_import_runs_time_order",
        ):
            with self.subTest(constraint=constraint_name):
                self.assertRegex(
                    integrity,
                    rf"constraint {constraint_name}[^;]+not valid",
                )

        for status_independent_constraint in (
            "chk_data_import_runs_nonblank_fields",
            "chk_data_import_runs_json_objects",
        ):
            with self.subTest(transition_guard=status_independent_constraint):
                self.assertNotRegex(
                    consistency,
                    rf"add constraint {status_independent_constraint}",
                )
        self.assertIn("validate_import_run_nonblank_fields", consistency)
        self.assertIn("validate_import_run_json_objects", consistency)

        self.assertRegex(
            foundation,
            r"update public\.place_operating_hours[^;]+set spans_next_day\s*=\s*true",
        )
        for constraint_name in (
            "ck_place_hours_closed_values",
            "ck_place_hours_time_order",
        ):
            with self.subTest(hours_constraint=constraint_name):
                self.assertRegex(
                    foundation,
                    rf"constraint {constraint_name}[^;]+not valid",
                )

        self.assertNotRegex(schedule, r"alter column trip_day_id set not null")
        self.assertIn("require_new_day_scoped_result", schedule)
        self.assertGreaterEqual(schedule.count("not valid"), 5)

        self.assertRegex(
            foundation,
            r"constraint fk_timetable_route_direction_stop[^;]+not valid",
        )
        self.assertNotRegex(
            consistency,
            r"alter table public\.timetable_entries[^;]+alter column city_code set not null",
        )
        self.assertIn("validate_timetable_source_scope", consistency)
        self.assertIn("legacy timetable source identity is immutable", consistency)
        timetable_backfill = re.search(
            r"update public\.timetable_entries timetable(?P<body>[^;]+);",
            consistency,
        )
        self.assertIsNotNone(timetable_backfill)
        self.assertNotRegex(timetable_backfill.group("body"), r"set source_provider")
        self.assertRegex(
            timetable_backfill.group("body"),
            r"route_stop\.source_provider\s*=\s*timetable\.source_provider",
        )
        self.assertNotRegex(
            consistency,
            r"foreign key\s*\([^)]*source_provider[^)]*\)\s*references",
        )
        self.assertIn("validate_route_stop_source_scope", consistency)
        self.assertIn("timetable source scope must match a valid route stop", consistency)

        for constraint_name in (
            "ck_bus_stops_node_id_nonblank",
            "ck_bus_stops_external_stop_id_nonblank",
            "ck_bus_stops_source_scope_nonblank",
            "ck_bus_routes_external_route_id_nonblank",
            "ck_bus_routes_source_scope_nonblank",
            "ck_place_images_image_url_nonblank",
            "ck_place_images_source_scope_nonblank",
            "ck_timetable_source_provider_nonblank",
            "ck_timetable_direction_key_nonblank",
            "ck_route_stops_source_provider_nonblank",
            "ck_route_stops_direction_key_nonblank",
            "ck_mobility_routes_source_scope_nonblank",
        ):
            with self.subTest(legacy_nonblank_constraint=constraint_name):
                self.assertRegex(
                    consistency,
                    rf"constraint {constraint_name}[^;]+btrim[^;]+not valid",
                )

        self.assertRegex(
            foundation,
            r"constraint ck_mobility_request_hash_nonblank[^;]+btrim[^;]+not valid",
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
        self.assertIn("validate_route_stop_source_scope", migration)
        self.assertIn("validate_timetable_source_scope", migration)
        self.assertRegex(
            migration,
            r"from public\.route_stops route_stop[^;]+join public\.bus_routes route"
            r"[^;]+join public\.bus_stops stop[^;]+for key share",
        )
        self.assertRegex(
            migration,
            r"place_operating_hours[^;]+last_entry_time[^;]+open_time[^;]+close_time",
        )
        self.assertRegex(
            migration,
            r"place_operating_hours[^;]+exclude using gist[^;]+is_closed\s+with\s+<>",
        )
        self.assertIn("validate_place_hours_cross_day_overlap", migration)
        self.assertIn("overnight operating hours overlap the next service day", migration)
        self.assertIn("legacy operating hours failed cross-day overlap audit", migration)
        self.assertLess(
            migration.index("legacy operating hours failed cross-day overlap audit"),
            migration.index("create trigger trg_place_hours_cross_day_overlap"),
        )
        self.assertRegex(
            migration,
            r"is_closed\s+and\s+(?:new\.|existing\.|overnight\.)?close_time\s*>\s*time '00:00'",
        )

        negative_contract = NEGATIVE_CONTRACT.read_text(encoding="utf-8").lower()
        for boundary_name in (
            "blank stop node id",
            "blank external stop id",
            "blank external route id",
            "blank place image url",
            "blank provider source scope",
            "blank mobility request hash",
            "midnight-ending overnight hours do not occupy the next day",
        ):
            with self.subTest(boundary_name=boundary_name):
                self.assertIn(boundary_name, negative_contract)

    def test_image_enrichment_reuses_the_same_provider_url_row(self):
        migration = self.read_migration(CONSISTENCY_MIGRATION)
        negative_contract = NEGATIVE_CONTRACT.read_text(encoding="utf-8").lower()

        self.assertIn("prevent_duplicate_place_image_source", migration)
        self.assertIn("pg_advisory_xact_lock", migration)
        self.assertIn("source_url_key", migration)
        self.assertRegex(
            migration,
            r"constraint uq_place_images_source_url_key\s+unique\s*\([^;]+source_url_key",
        )
        self.assertRegex(
            migration,
            r"before insert or update of[^;]+source_url_key[^;]+on public\.place_images",
        )
        self.assertIn("on conflict on constraint uq_place_images_source_url_key", negative_contract)
        self.assertIn("image source url key cannot be cleared", negative_contract)
        self.assertIn(
            "image url-only row must be updated instead of duplicated during enrichment",
            negative_contract,
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
