from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260830000000_schedule_revision_run_foundation.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME


def compact_sql(contents: str) -> str:
    return re.sub(r"\s+", " ", contents.lower()).strip()


def assert_revision_negative_fixture_is_self_contained(section: str) -> None:
    seed_only_ids = (
        "09000000-0000-0000-0000-000000000001",
        "50000000-0000-0000-0000-000000000001",
        "51000000-0000-0000-0000-000000000001",
        "60000000-0000-0000-0000-000000000001",
    )
    if any(seed_id in section for seed_id in seed_only_ids):
        raise AssertionError("schedule revision 음수 계약이 demo seed identity에 의존합니다.")
    required_lineages = (
        "f1600000-0000-0000-0000-000000000001",
        "f1610000-0000-0000-0000-000000000001",
        "f1620000-0000-0000-0000-000000000001",
        "f1630000-0000-0000-0000-000000000001",
        "f1700000-0000-0000-0000-000000000002",
        "f1710000-0000-0000-0000-000000000002",
        "f1720000-0000-0000-0000-000000000002",
        "f1730000-0000-0000-0000-000000000002",
    )
    if any(identity not in section for identity in required_lineages):
        raise AssertionError("schedule revision 음수 계약에는 정확히 두 독립 lineage가 필요합니다.")
    if section.count("select public.create_local_test_user(") != 2:
        raise AssertionError("두 lineage의 canonical Auth user fixture가 필요합니다.")
    expected_structural_tuples = {
        "lineage-a-trip": (
            "( 'f1610000-0000-0000-0000-000000000001', "
            "'f1600000-0000-0000-0000-000000000001', "
            "'revision-negative-owner-trip', current_date, current_date, 'fixture', 'contract-v1' )"
        ),
        "lineage-b-trip": (
            "( 'f1710000-0000-0000-0000-000000000002', "
            "'f1700000-0000-0000-0000-000000000002', "
            "'revision-negative-other-trip', current_date, current_date, 'fixture', 'contract-v1' )"
        ),
        "lineage-a-day": (
            "( 'f1620000-0000-0000-0000-000000000001', "
            "'f1610000-0000-0000-0000-000000000001', 1, current_date )"
        ),
        "lineage-b-day": (
            "( 'f1720000-0000-0000-0000-000000000002', "
            "'f1710000-0000-0000-0000-000000000002', 1, current_date )"
        ),
        "lineage-a-version": (
            "( 'f1630000-0000-0000-0000-000000000001', "
            "'f1610000-0000-0000-0000-000000000001', 1, 'draft', 'initial', "
            "'f1600000-0000-0000-0000-000000000001' )"
        ),
        "lineage-b-version": (
            "( 'f1730000-0000-0000-0000-000000000002', "
            "'f1710000-0000-0000-0000-000000000002', 1, 'draft', 'initial', "
            "'f1700000-0000-0000-0000-000000000002' )"
        ),
        "valid-a": (
            "values ( 'f1640000-0000-0000-0000-000000000001', "
            "'f1600000-0000-0000-0000-000000000001', "
            "'f1610000-0000-0000-0000-000000000001', "
            "'f1630000-0000-0000-0000-000000000001', "
            "'f1620000-0000-0000-0000-000000000001', 'revision-v1', "
            "'algorithm-v1', 'f1650000-0000-0000-0000-000000000001', repeat('a', 64) )"
        ),
        "owner-only-b": (
            "'schedule revision owner lineage mismatch', $statement$ insert into "
            "public.schedule_revision_runs ( owner_user_id, trip_plan_id, "
            "base_schedule_version_id, target_trip_day_id, contract_version, "
            "algorithm_version, idempotency_key, request_hash ) values ( "
            "'f1700000-0000-0000-0000-000000000002', "
            "'f1610000-0000-0000-0000-000000000001', "
            "'f1630000-0000-0000-0000-000000000001', "
            "'f1620000-0000-0000-0000-000000000001'"
        ),
        "base-only-b": (
            "'schedule revision base lineage mismatch', $statement$ insert into "
            "public.schedule_revision_runs ( owner_user_id, trip_plan_id, "
            "base_schedule_version_id, target_trip_day_id, contract_version, "
            "algorithm_version, idempotency_key, request_hash ) values ( "
            "'f1600000-0000-0000-0000-000000000001', "
            "'f1610000-0000-0000-0000-000000000001', "
            "'f1730000-0000-0000-0000-000000000002', "
            "'f1620000-0000-0000-0000-000000000001'"
        ),
        "day-only-b": (
            "'schedule revision day lineage mismatch', $statement$ insert into "
            "public.schedule_revision_runs ( owner_user_id, trip_plan_id, "
            "base_schedule_version_id, target_trip_day_id, contract_version, "
            "algorithm_version, idempotency_key, request_hash ) values ( "
            "'f1600000-0000-0000-0000-000000000001', "
            "'f1610000-0000-0000-0000-000000000001', "
            "'f1630000-0000-0000-0000-000000000001', "
            "'f1720000-0000-0000-0000-000000000002'"
        ),
    }
    for label, expected_tuple in expected_structural_tuples.items():
        if expected_tuple not in section:
            raise AssertionError(f"schedule revision 구조 tuple drift: {label}")
    for mismatch_label in (
        "schedule revision owner lineage mismatch",
        "schedule revision base lineage mismatch",
        "schedule revision day lineage mismatch",
    ):
        mismatch = section.split(mismatch_label, 1)[1]
        mismatch = mismatch.split("select pg_temp.expect_rejected", 1)[0]
        if "array['23503']" not in mismatch:
            raise AssertionError(f"schedule revision FK SQLSTATE drift: {mismatch_label}")


def mutate_after(section: str, marker: str, old: str, new: str) -> str:
    prefix, found, suffix = section.partition(marker)
    if not found or old not in suffix:
        raise AssertionError("mutation target을 찾지 못했습니다.")
    return prefix + found + suffix.replace(old, new, 1)


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

    def test_revision_negative_fixtures_do_not_depend_on_demo_seed(self):
        negative = compact_sql(
            (ROOT / "db/queries/database_negative_constraints.sql").read_text(
                encoding="utf-8"
            )
        )
        section = negative.split("select public.create_local_test_user(", 1)[1]
        section = "select public.create_local_test_user(" + section
        section = section.split("insert into public.compute_run_inputs", 1)[0]

        assert_revision_negative_fixture_is_self_contained(section)
        for seed_mutation in (
            section.replace(
                "f1600000-0000-0000-0000-000000000001",
                "09000000-0000-0000-0000-000000000001",
                1,
            ),
            section.replace(
                "f1710000-0000-0000-0000-000000000002",
                "50000000-0000-0000-0000-000000000001",
                1,
            ),
        ):
            with self.subTest(seed_mutation=seed_mutation):
                with self.assertRaisesRegex(AssertionError, "demo seed"):
                    assert_revision_negative_fixture_is_self_contained(seed_mutation)

        user_a = "f1600000-0000-0000-0000-000000000001"
        trip_a = "f1610000-0000-0000-0000-000000000001"
        day_a = "f1620000-0000-0000-0000-000000000001"
        version_a = "f1630000-0000-0000-0000-000000000001"
        user_b = "f1700000-0000-0000-0000-000000000002"
        trip_b = "f1710000-0000-0000-0000-000000000002"
        day_b = "f1720000-0000-0000-0000-000000000002"
        version_b = "f1730000-0000-0000-0000-000000000002"
        non_target_fk_mutations = (
            ("schedule revision owner lineage mismatch", trip_a, trip_b),
            ("schedule revision owner lineage mismatch", version_a, version_b),
            ("schedule revision owner lineage mismatch", day_a, day_b),
            ("schedule revision base lineage mismatch", user_a, user_b),
            ("schedule revision base lineage mismatch", trip_a, trip_b),
            ("schedule revision base lineage mismatch", day_a, day_b),
            ("schedule revision day lineage mismatch", user_a, user_b),
            ("schedule revision day lineage mismatch", trip_a, trip_b),
            ("schedule revision day lineage mismatch", version_a, version_b),
        )
        relationship_mutations = (
            mutate_after(section, "insert into public.trip_plans", user_a, user_b),
            mutate_after(section, "insert into public.trip_days", trip_a, trip_b),
            mutate_after(
                section, "insert into public.trip_schedule_versions", trip_a, trip_b
            ),
        )
        for marker, old, new in non_target_fk_mutations:
            relationship_mutations += (mutate_after(section, marker, old, new),)
        for structural_mutation in relationship_mutations:
            with self.subTest(structural_mutation=structural_mutation):
                with self.assertRaisesRegex(AssertionError, "구조 tuple"):
                    assert_revision_negative_fixture_is_self_contained(
                        structural_mutation
                    )

    def test_database_contracts_use_the_local_auth_fixture_seam(self):
        auth_compat = compact_sql(
            (ROOT / "db/local-postgres/auth_compat.sql").read_text(encoding="utf-8")
        )
        contract_paths = (
            ROOT / "db/queries/database_negative_constraints.sql",
            ROOT / "db/queries/database_concurrency_contract.sql",
        )

        self.assertIn("create or replace function public.create_local_test_user", auth_compat)
        for contract_path in contract_paths:
            contents = compact_sql(contract_path.read_text(encoding="utf-8"))
            with self.subTest(contract=contract_path.name):
                self.assertNotRegex(contents, r"insert into auth\.users")
                self.assertIn("public.create_local_test_user", contents)

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
