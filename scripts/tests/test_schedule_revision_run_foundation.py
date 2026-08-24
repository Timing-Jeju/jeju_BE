from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260830000000_schedule_revision_run_foundation.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME


def compact_sql(contents: str) -> str:
    return re.sub(r"\s+", " ", contents.lower()).strip()


class ScheduleRevisionRunFoundationTest(unittest.TestCase):
    def migration(self) -> str:
        self.assertTrue(
            MIGRATION.is_file(),
            f"schedule revision run append-only migration이 없습니다: {MIGRATION_NAME}",
        )
        return compact_sql(MIGRATION.read_text(encoding="utf-8"))

    def test_append_only_migration_is_mounted_before_seed_in_all_postgres_composes(self):
        mount = f"./supabase/migrations/{MIGRATION_NAME}"
        seed = "./db/local-postgres/seed_fixtures.sql"

        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            contents = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertIn(mount, contents)
                self.assertLess(contents.index(mount), contents.index(seed))

    def test_identity_and_canonical_lineage_use_real_composite_foreign_keys(self):
        migration = self.migration()

        self.assertIn("create table public.schedule_revision_runs", migration)
        for column in (
            "id uuid primary key default gen_random_uuid()",
            "owner_user_id uuid not null",
            "trip_plan_id uuid not null",
            "base_schedule_version_id uuid not null",
            "target_trip_day_id uuid not null",
        ):
            with self.subTest(column=column):
                self.assertIn(column, migration)

        self.assertRegex(
            migration,
            r"foreign key \(trip_plan_id, owner_user_id\) references public\.trip_plans \(id, user_id\)",
        )
        self.assertRegex(
            migration,
            r"foreign key \(base_schedule_version_id, trip_plan_id\) references public\.trip_schedule_versions \(id, trip_plan_id\)",
        )
        self.assertRegex(
            migration,
            r"foreign key \(target_trip_day_id, trip_plan_id\) references public\.trip_days \(id, trip_plan_id\)",
        )
        self.assertNotIn("schedule_revision_run_id uuid", migration)

    def test_owner_trip_foreign_key_has_an_exact_nonunique_leading_index(self):
        migration = self.migration()
        schema_contract = compact_sql(
            (ROOT / "db/queries/schema_contract.sql").read_text(encoding="utf-8")
        )

        self.assertIn(
            "create index idx_schedule_revision_runs_trip_owner_fk "
            "on public.schedule_revision_runs (trip_plan_id, owner_user_id)",
            migration,
        )
        self.assertNotIn(
            "create unique index idx_schedule_revision_runs_trip_owner_fk", migration
        )
        self.assertIn("foreign keys without a leading index", schema_contract)
        self.assertIn("revoke all on public.schedule_revision_runs from anon", migration)
        self.assertIn("revoke all on public.schedule_revision_runs from authenticated", migration)
        self.assertIn(
            "grant select, insert, update, delete on public.schedule_revision_runs to service_role",
            migration,
        )

    def test_lifecycle_is_closed_and_worker_runtime_columns_are_bundled(self):
        migration = self.migration()

        self.assertRegex(
            migration,
            r"check \(status in \('queued', 'running', 'succeeded', 'failed', 'cancelled'\)\)",
        )
        for column in (
            "attempt_count integer not null default 0",
            "fencing_token bigint not null default 0",
            "lease_owner text",
            "lease_expires_at timestamptz",
            "heartbeat_at timestamptz",
            "next_attempt_at timestamptz",
        ):
            with self.subTest(column=column):
                self.assertIn(column, migration)
        self.assertIn("chk_schedule_revision_runs_worker_fields", migration)
        self.assertIn("chk_schedule_revision_runs_execution_phase", migration)
        self.assertIn("failure_code varchar(100)", migration)
        self.assertRegex(
            migration,
            r"status = 'queued' and attempt_count = 0 and failure_code is null",
        )
        self.assertRegex(
            migration,
            r"status = 'queued' and attempt_count > 0 and failure_code is not null",
        )
        self.assertRegex(
            migration,
            r"status in \('failed', 'cancelled'\) and failure_code is not null",
        )
        self.assertRegex(
            migration,
            r"status in \('running', 'succeeded'\) and failure_code is null",
        )
        self.assertIn("protect_schedule_revision_run_lifecycle", migration)
        self.assertIn("schedule revision run terminal status is immutable", migration)
        for transition_guard in (
            "schedule revision run claim must advance attempt and fencing token exactly once",
            "schedule revision run heartbeat cannot change owner or fencing counters",
            "schedule revision run live lease cannot be reclaimed",
            "schedule revision run retry must preserve fencing counters and failure code",
            "schedule revision run terminal transition must preserve fencing counters",
            "schedule revision run fifth attempt cannot be retried",
            "schedule revision run exhausted recovery requires expired fifth attempt",
        ):
            with self.subTest(transition_guard=transition_guard):
                self.assertIn(transition_guard, migration)
        self.assertIn("old.attempt_count < 5", migration)
        self.assertIn("old.attempt_count = 5", migration)
        self.assertIn("new.failure_code = 'async_run_retry_exhausted'", migration)

    def test_idempotency_hash_and_active_scope_are_declaratively_unique(self):
        migration = self.migration()

        self.assertIn("idempotency_key uuid not null", migration)
        self.assertIn("request_hash char(64) not null", migration)
        self.assertRegex(migration, r"request_hash ~ '\^\[0-9a-f\]\{64\}\$'")
        self.assertIn("uq_schedule_revision_runs_idempotency", migration)
        self.assertIn("unique (owner_user_id, trip_plan_id, idempotency_key)", migration)
        self.assertRegex(
            migration,
            r"create unique index uq_schedule_revision_runs_active_scope .*"
            r"\(owner_user_id, trip_plan_id, base_schedule_version_id, target_trip_day_id\) "
            r"where status in \('queued', 'running'\)",
        )

    def test_creation_identity_and_versions_are_immutable(self):
        migration = self.migration()
        guard = re.search(
            r"create function public\.protect_schedule_revision_run_lifecycle\(\).*?as \$\$(.*?)\$\$;",
            migration,
        )

        self.assertIsNotNone(guard)
        definition = guard.group(1)
        for field in (
            "id",
            "owner_user_id",
            "trip_plan_id",
            "base_schedule_version_id",
            "target_trip_day_id",
            "contract_version",
            "algorithm_version",
            "idempotency_key",
            "request_hash",
            "created_at",
        ):
            with self.subTest(field=field):
                self.assertIn(f"old.{field} is distinct from new.{field}", definition)
        self.assertIn("new.status <> 'queued'", definition)
        self.assertIn("new.fencing_token <> 0", definition)
        self.assertIn("new.attempt_count <> 0", definition)

    def test_rls_and_acl_expose_only_the_server_boundary(self):
        migration = self.migration()

        self.assertIn(
            "alter table public.schedule_revision_runs enable row level security",
            migration,
        )
        self.assertNotIn("create policy", migration)
        self.assertIn("revoke all on public.schedule_revision_runs from anon", migration)
        self.assertIn("revoke all on public.schedule_revision_runs from authenticated", migration)
        self.assertIn(
            "grant select, insert, update, delete on public.schedule_revision_runs to service_role",
            migration,
        )
        self.assertNotIn("grant truncate", migration)

    def test_schema_smoke_docs_dbml_and_upgrade_sequence_include_the_foundation(self):
        schema_contract = compact_sql(
            (ROOT / "db/queries/schema_contract.sql").read_text(encoding="utf-8")
        )
        smoke_contract = compact_sql(
            (ROOT / "db/queries/smoke_check.sql").read_text(encoding="utf-8")
        )
        docker_smoke = (ROOT / "scripts/docker-smoke-test.sh").read_text(encoding="utf-8")
        db_readme = (ROOT / "db/README.md").read_text(encoding="utf-8")
        architecture = (ROOT / "docs/ARCHITECTURE.md").read_text(encoding="utf-8")
        schema_doc = (ROOT / "docs/designs/timing-jeju-db-schema-v0.md").read_text(
            encoding="utf-8"
        )
        dbml = (ROOT / "docs/designs/timing-jeju-dbdiagram.dbml").read_text(
            encoding="utf-8"
        )

        for contents in (schema_contract, smoke_contract):
            self.assertIn("schedule_revision_runs", contents)
        self.assertGreaterEqual(
            docker_smoke.count("/docker-entrypoint-initdb.d/028_schedule_revision_run_foundation.sql"),
            2,
        )
        for contents in (db_readme, architecture, schema_doc, dbml):
            self.assertIn("schedule_revision_runs", contents)

    def test_negative_and_two_session_contracts_cover_revision_run_invariants(self):
        negative = compact_sql(
            (ROOT / "db/queries/database_negative_constraints.sql").read_text(
                encoding="utf-8"
            )
        )
        concurrency = compact_sql(
            (ROOT / "db/queries/database_concurrency_contract.sql").read_text(
                encoding="utf-8"
            )
        )

        for expected in (
            "schedule revision owner lineage mismatch",
            "schedule revision base lineage mismatch",
            "schedule revision day lineage mismatch",
            "schedule revision identity is immutable",
            "schedule revision terminal rollback",
        ):
            with self.subTest(negative=expected):
                self.assertIn(expected, negative)
        self.assertIn("schedule_revision_idempotency", concurrency)
        self.assertIn("schedule revision concurrent idempotency did not canonicalize", concurrency)
        self.assertRegex(
            concurrency, r"dblink_send_query\(\s*'schedule_revision_b'"
        )

    def test_database_contracts_use_the_local_auth_fixture_seam(self):
        auth_compat = compact_sql(
            (ROOT / "db/local-postgres/auth_compat.sql").read_text(encoding="utf-8")
        )
        contract_paths = (
            ROOT / "db/queries/database_negative_constraints.sql",
            ROOT / "db/queries/database_concurrency_contract.sql",
        )

        self.assertIn("create or replace function auth.create_local_test_user", auth_compat)
        for contract_path in contract_paths:
            contents = compact_sql(contract_path.read_text(encoding="utf-8"))
            with self.subTest(contract=contract_path.name):
                self.assertNotRegex(contents, r"insert into auth\.users")
                self.assertIn("auth.create_local_test_user", contents)

    def test_scope_does_not_extend_http_input_snapshot_or_mcp_call_log(self):
        migration = self.migration()

        for excluded in (
            "mcp_compute_call_logs",
            "compute_run_inputs",
            "structured_input",
            "raw_request",
            "controller",
        ):
            with self.subTest(excluded=excluded):
                self.assertNotIn(excluded, migration)


if __name__ == "__main__":
    unittest.main()
