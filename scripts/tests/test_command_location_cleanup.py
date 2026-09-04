from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260914000000_compute_run_input_location_cleanup.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME
PREDECESSOR = ROOT / "supabase/migrations/20260831000000_compute_run_input_snapshot.sql"


def compact_sql(contents: str) -> str:
    return re.sub(r"\s+", " ", contents.lower()).strip()


def cleanup_contract_violations(contents: str) -> list[str]:
    sql = compact_sql(contents)
    required = (
        "drop constraint chk_compute_run_inputs_location",
        "add constraint chk_compute_run_inputs_location",
        "location_redacted_at is not null and coarse_location is null and location_precision_meters is null and location_policy_version is null and location_observed_at is null and location_expires_at is null",
        "create or replace function public.protect_compute_run_input_immutability()",
        "create function public.redact_due_compute_run_input_locations( evaluated_at timestamptz, batch_size integer ) returns integer",
        "security definer set search_path = ''",
        "evaluated_at is null",
        "batch_size is null or batch_size < 1 or batch_size > 500",
        "location_supplied and location_redacted_at is null and location_expires_at is not null and location_expires_at <= evaluated_at",
        "order by location_expires_at, id limit batch_size for update skip locked",
        "set coarse_location = null, location_precision_meters = null, location_policy_version = null, location_observed_at = null, location_expires_at = null, location_redacted_at = evaluated_at",
        "revoke all on function public.redact_due_compute_run_input_locations(timestamptz, integer) from public",
        "revoke all on function public.redact_due_compute_run_input_locations(timestamptz, integer) from anon",
        "revoke all on function public.redact_due_compute_run_input_locations(timestamptz, integer) from authenticated",
        "grant execute on function public.redact_due_compute_run_input_locations(timestamptz, integer) to service_role",
    )
    violations = [fragment for fragment in required if fragment not in sql]
    forbidden = (
        "grant update on public.compute_run_inputs to service_role",
        "grant all on public.compute_run_inputs to service_role",
        "set search_path = public",
    )
    violations.extend(fragment for fragment in forbidden if fragment in sql)
    return violations


class CommandLocationCleanupMigrationContractTest(unittest.TestCase):
    def migration(self) -> str:
        self.assertTrue(MIGRATION.is_file(), f"additive migration이 없습니다: {MIGRATION_NAME}")
        return MIGRATION.read_text(encoding="utf-8")

    def test_additive_migration_is_mounted_after_all_existing_migrations(self):
        mount = f"./supabase/migrations/{MIGRATION_NAME}"
        previous_mount = (
            "./supabase/migrations/20260904000001_push_notification_server_writer_boundary.sql"
        )
        seed = "./db/local-postgres/seed_fixtures.sql"
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            contents = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertIn(mount, contents)
                self.assertLess(contents.index(previous_mount), contents.index(mount))
                self.assertLess(contents.index(mount), contents.index(seed))
        docker_smoke = (ROOT / "scripts/docker-smoke-test.sh").read_text(encoding="utf-8")
        self.assertEqual(
            2,
            docker_smoke.count(
                "/docker-entrypoint-initdb.d/044_compute_run_input_location_cleanup.sql"
            ),
        )

    def test_five_field_atomic_redaction_stable_batch_and_exact_privileges(self):
        self.assertEqual(cleanup_contract_violations(self.migration()), [])
        predecessor = PREDECESSOR.read_text(encoding="utf-8")
        self.assertNotIn("redact_due_compute_run_input_locations", predecessor)
        for contract_path in (
            ROOT / "db/queries/schema_contract.sql",
            ROOT / "db/queries/smoke_check.sql",
            ROOT / "db/queries/database_concurrency_contract.sql",
            ROOT / "db/README.md",
            ROOT / "docs/ARCHITECTURE.md",
        ):
            with self.subTest(contract=contract_path.name):
                self.assertIn(
                    "redact_due_compute_run_input_locations",
                    contract_path.read_text(encoding="utf-8"),
                )

    def test_contract_is_mutation_sensitive_to_expiry_fields_and_privilege_widening(self):
        migration = self.migration()
        mutations = {
            "equality becomes future-only": migration.replace(
                "location_expires_at <= evaluated_at",
                "location_expires_at < evaluated_at",
                1,
            ),
            "one location field survives": migration.replace(
                "location_policy_version = null,", "location_policy_version = location_policy_version,", 1
            ),
            "service role gets broad update": migration
            + "\ngrant update on public.compute_run_inputs to service_role;\n",
            "stable id tiebreaker removed": migration.replace(
                "order by location_expires_at, id",
                "order by location_expires_at",
                1,
            ),
            "skip locked removed": migration.replace(
                "for update skip locked", "for update", 1
            ),
        }
        for label, mutated in mutations.items():
            with self.subTest(label=label):
                self.assertNotEqual(cleanup_contract_violations(mutated), [])

    def test_501_fixture_proves_id_order_and_both_pg_smokes_replay_concurrency(self):
        integration = (
            ROOT
            / "services/spring-api/src/test/java/com/timingjeju/api/support/postgresql"
            / "CommandInputSnapshotRepositoryIntegrationTest.java"
        ).read_text(encoding="utf-8")
        self.assertIn("lpad((502 - series)::text, 12, '0')", integration)
        self.assertIn("order by location_expires_at, id", integration)
        self.assertIn(
            'UUID.fromString("10931000-0000-0000-0000-000000000501")',
            integration,
        )
        concurrency = "db/queries/database_concurrency_contract.sql"
        self.assertIn(
            "/queries/database_concurrency_contract.sql",
            (ROOT / "scripts/docker-smoke-test.sh").read_text(encoding="utf-8"),
        )
        self.assertIn(
            concurrency,
            (ROOT / "scripts/supabase-smoke-test.sh").read_text(encoding="utf-8"),
        )

    def test_future_mcp_worker_handoff_requires_immediate_location_admission(self):
        required = (
            "현재 production에는 MCP 계산 worker와 argument assembler가 없습니다",
            "McpCommandLocationResolver.resolveImmediatelyBeforeMcp",
            "MCP argument 조립 직전에 반드시 호출",
        )
        for contract_path in (
            ROOT / "docs/ARCHITECTURE.md",
            ROOT / "docs/designs/timing-jeju-db-schema-v0.md",
        ):
            contents = contract_path.read_text(encoding="utf-8")
            with self.subTest(contract=contract_path.name):
                for fragment in required:
                    self.assertIn(fragment, contents)


if __name__ == "__main__":
    unittest.main()
