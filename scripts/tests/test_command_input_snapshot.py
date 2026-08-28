from __future__ import annotations

import re
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260831000000_compute_run_input_snapshot.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME
ACTUAL_PG_TEST = (
    ROOT
    / "services/spring-api/src/test/java/com/timingjeju/api/support/postgresql"
    / "CommandInputSnapshotRepositoryIntegrationTest.java"
)
NEGATIVE_CONSTRAINTS = ROOT / "db/queries/database_negative_constraints.sql"
OTHER_SCRIPT_TEST = ROOT / "scripts/tests/test_backend_layout.py"
SELF = Path(__file__).resolve()


def repository_raw_sql_sources() -> tuple[Path, ...]:
    sources = set((ROOT / "db").rglob("*.sql"))
    sources.update((ROOT / "supabase/migrations").rglob("*.sql"))
    for pattern in ("*.py", "*.sh"):
        sources.update(
            path
            for path in (ROOT / "scripts").rglob(pattern)
            if path.resolve() != SELF
        )
    for source_set in ("main", "test"):
        sources.update(
            (ROOT / f"services/spring-api/src/{source_set}").rglob("*.java")
        )
    return tuple(sorted(sources))


def compact_sql(contents: str) -> str:
    return re.sub(r"\s+", " ", contents.lower()).strip()


EXACT_COMMAND_INPUT_HASH_CALL = re.compile(
    r"public\.compute_command_input_hash\(\s*"
    r"(?:'[^']+'|\?)::text,\s*"
    r"(?:[0-9]+|\?)::smallint,\s*"
    r"(?:'[^']+'|\?)::text,\s*"
    r"(?:'[^']+'|\?)::text,\s*"
    r"(?:'[^']+'|\?)::uuid,\s*"
    r"(?:'[^']*'|\?)::jsonb,\s*"
    r"(?:true|false|\?)::boolean,\s*"
    r"(?:null|'[^']*'|\?)::jsonb\s*\)"
)
HASH_CALL = re.compile(r"public\.compute_command_input_hash\(")
MIGRATION_HASH_DEFINITION = re.compile(
    r"public\.compute_command_input_hash\(\s*"
    r"input_run_type text,\s*input_schema_version smallint,\s*"
    r"input_contract_version text,\s*input_algorithm_version text,\s*"
    r"input_base_schedule_version_id uuid,\s*input_structured_input jsonb,\s*"
    r"input_location_supplied boolean,\s*input_coarse_location jsonb\s*\)"
)
MIGRATION_HASH_PRIVILEGE_SIGNATURE = re.compile(
    r"public\.compute_command_input_hash\("
    r"text, smallint, text, text, uuid, jsonb, boolean, jsonb"
    r"\) from public"
)
MIGRATION_TYPED_INTERNAL_HASH_CALL = re.compile(
    r"public\.compute_command_input_hash\(\s*"
    r"new\.run_type,\s*new\.schema_version,\s*"
    r"new\.contract_version,\s*new\.algorithm_version,\s*"
    r"new\.base_schedule_version_id,\s*new\.structured_input,\s*"
    r"new\.location_supplied,\s*new\.coarse_location\s*\)"
)
EXPECTED_HASH_OCCURRENCE_COUNTS = Counter(
    {
        "direct exact": 2,
        "migration definition": 1,
        "migration privilege signature": 1,
        "migration typed internal": 1,
    }
)


def invalid_direct_hash_calls(contents: str) -> list[int]:
    source = compact_sql(contents)
    return [
        match.start()
        for match in HASH_CALL.finditer(source)
        if EXACT_COMMAND_INPUT_HASH_CALL.match(source, match.start()) is None
    ]


def classify_hash_occurrence(path: Path, contents: str, start: int) -> str:
    prefix = contents[max(0, start - 40) : start]
    if path == MIGRATION:
        if prefix.endswith("create function ") and MIGRATION_HASH_DEFINITION.match(
            contents, start
        ):
            return "migration definition"
        if prefix.endswith(
            "revoke all on function "
        ) and MIGRATION_HASH_PRIVILEGE_SIGNATURE.match(contents, start):
            return "migration privilege signature"
        if MIGRATION_TYPED_INTERNAL_HASH_CALL.match(contents, start):
            return "migration typed internal"
    if EXACT_COMMAND_INPUT_HASH_CALL.match(contents, start):
        return "direct exact"
    return "invalid"


def repository_hash_occurrence_counts(
    overrides: dict[Path, str] | None = None,
) -> Counter[str]:
    override_contents = overrides or {}
    paths = set(repository_raw_sql_sources()) | set(override_contents)
    counts: Counter[str] = Counter()
    for path in paths:
        contents = compact_sql(
            override_contents.get(path, path.read_text(encoding="utf-8"))
        )
        for occurrence in HASH_CALL.finditer(contents):
            counts[classify_hash_occurrence(path, contents, occurrence.start())] += 1
    return counts


def repository_hash_contract_violations(
    overrides: dict[Path, str] | None = None,
) -> list[str]:
    actual = repository_hash_occurrence_counts(overrides)
    return [
        f"{classification}: expected {expected}, actual {actual[classification]}"
        for classification, expected in EXPECTED_HASH_OCCURRENCE_COUNTS.items()
        if actual[classification] != expected
    ] + [
        f"{classification}: expected 0, actual {count}"
        for classification, count in actual.items()
        if classification not in EXPECTED_HASH_OCCURRENCE_COUNTS and count != 0
    ]


class CommandInputSnapshotContractTest(unittest.TestCase):
    def migration(self) -> str:
        self.assertTrue(MIGRATION.is_file(), f"append-only migration이 없습니다: {MIGRATION_NAME}")
        return compact_sql(MIGRATION.read_text(encoding="utf-8"))

    def test_migration_is_mounted_after_revision_foundation_and_before_seed(self):
        mount = f"./supabase/migrations/{MIGRATION_NAME}"
        foundation = "./supabase/migrations/20260830000000_schedule_revision_run_foundation.sql"
        seed = "./db/local-postgres/seed_fixtures.sql"
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            contents = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertIn(mount, contents)
                self.assertLess(contents.index(foundation), contents.index(mount))
                self.assertLess(contents.index(mount), contents.index(seed))

    def test_exactly_one_real_parent_fk_and_per_parent_uniqueness_are_declared(self):
        migration = self.migration()
        self.assertIn("create table public.compute_run_inputs", migration)
        self.assertIn("num_nonnulls(compute_run_id, generation_run_id, schedule_revision_run_id) = 1", migration)
        for column, table in (
            ("compute_run_id", "compute_runs"),
            ("generation_run_id", "itinerary_generation_runs"),
            ("schedule_revision_run_id", "schedule_revision_runs"),
        ):
            with self.subTest(parent=column):
                self.assertRegex(migration, rf"foreign key \({column}\) references public\.{table} \(id\) on delete cascade")
                self.assertRegex(migration, rf"create unique index .* on public\.compute_run_inputs \({column}\) where {column} is not null")
        self.assertNotIn("placeholder", migration)

    def test_lineage_type_schema_json_hash_and_sensitive_key_guards_are_closed(self):
        migration = self.migration()
        for expected in (
            "foreign key (trip_plan_id, owner_user_id)",
            "foreign key (base_schedule_version_id, trip_plan_id)",
            "input_schema_version <> 1 or jsonb_typeof(value) <> 'object'",
            "command_input_hash ~ '^[0-9a-f]{64}$'",
            "validate_compute_run_input_parent",
            "canonicalize_command_jsonb",
            "compute_command_input_hash",
            "command input hash mismatch",
            "command_input_matches_schema",
            "command input does not match closed schema",
        ):
            with self.subTest(expected=expected):
                self.assertIn(expected, migration)
        self.assertNotIn("command_json_contains_forbidden_key", migration)
        self.assertNotIn("forbidden_keys", migration)
        for run_type, fields in {
            "itinerary_generation": ("targetdayid", "candidatecount", "refreshexternalfacts"),
            "schedule_revision": ("targetdayid", "affecteditemids", "instructioncodes"),
            "itinerary_validate": ("targetdayid",),
            "feasibility": ("refreshexternalfacts",),
            "spare_time": ("targetdayid", "windowstart", "windowend"),
            "recovery": ("riskeventid", "optioncount"),
            "live_recalculate": ("executioneventid", "refreshexternalfacts"),
        }.items():
            with self.subTest(run_type=run_type):
                self.assertIn(f"'{run_type}'", migration)
                for field in fields:
                    self.assertIn(f"'{field}'", migration)

    def test_trip_end_anchor_is_db_recorded_once_and_never_uses_updated_at(self):
        migration = self.migration()
        self.assertIn("add column trip_ended_at timestamptz", migration)
        self.assertIn("record_trip_ended_at", migration)
        self.assertIn("old.trip_ended_at is distinct from new.trip_ended_at", migration)
        helper = re.search(
            r"create function public\.compute_run_input_known_expiry\((.*?)\).*?as \$\$(.*?)\$\$;",
            migration,
        )
        self.assertIsNotNone(helper)
        self.assertIn("trip_ended_at", helper.group(2))
        self.assertNotIn("updated_at", helper.group(2))

    def test_spare_time_uses_shared_canonical_rfc3339_subset(self):
        migration = self.migration()
        self.assertIn("command_input_rfc3339_timestamp_is_valid", migration)
        self.assertIn("make_date", migration)
        self.assertIn("between 1 and 9999", migration)
        self.assertIn("between 0 and 23", migration)
        self.assertIn("between 0 and 59", migration)
        self.assertIn("offset_hour = 18 and offset_minute <> 0", migration)
        self.assertIn("[0-9]{1,9}", migration)

    def test_jsonb_object_size_helper_is_total_for_non_objects(self):
        migration = self.migration()
        helper = re.search(
            r"create function public\.command_jsonb_object_size\(value jsonb\).*?as \$\$(.*?)\$\$;",
            migration,
        )
        self.assertIsNotNone(helper)
        self.assertIn("jsonb_typeof(value) <> 'object'", helper.group(1))
        self.assertIn("return null", helper.group(1))
        self.assertIn("from jsonb_object_keys(value)", helper.group(1))
        self.assertIn(
            "grant execute on function public.command_jsonb_object_size(jsonb) to service_role",
            migration,
        )

    def test_repository_direct_hash_calls_use_all_exact_declared_types(self):
        self.assertEqual(
            EXPECTED_HASH_OCCURRENCE_COUNTS,
            repository_hash_occurrence_counts(),
        )
        self.assertFalse(repository_hash_contract_violations())

    def test_repository_hash_call_guard_rejects_uncast_placeholder_first_argument(self):
        valid_call = """
            select public.compute_command_input_hash(
              'feasibility'::text, 1::smallint, 'command/v1'::text,
              'algorithm/v1'::text, ?::uuid, '{}'::jsonb,
              false::boolean, null::jsonb
            )
        """
        mutated_call = valid_call.replace("'feasibility'::text", "?")
        self.assertEqual(1, len(invalid_direct_hash_calls(mutated_call)))

    def test_repository_hash_call_inventory_rejects_removed_known_call(self):
        contents = (ROOT / "db/queries/database_negative_constraints.sql").read_text(
            encoding="utf-8"
        )
        removed = contents.replace(
            "public.compute_command_input_hash(", "public.removed_command_input_hash(", 1
        )
        self.assertTrue(
            repository_hash_contract_violations(
                {ROOT / "db/queries/database_negative_constraints.sql": removed}
            )
        )

    def test_repository_hash_call_inventory_rejects_added_valid_call(self):
        contents = OTHER_SCRIPT_TEST.read_text(encoding="utf-8")
        added = contents + """
        # raw SQL fixture:
        # select public.compute_command_input_hash(
        #   ?::text, ?::smallint, ?::text, ?::text,
        #   ?::uuid, ?::jsonb, ?::boolean, ?::jsonb
        # )
        """
        self.assertTrue(repository_hash_contract_violations({OTHER_SCRIPT_TEST: added}))

    def test_repository_hash_call_inventory_validates_all_typed_internal_arguments(self):
        contents = MIGRATION.read_text(encoding="utf-8")
        mutated = contents.replace(
            "    new.location_supplied,\n    new.coarse_location\n  ) then",
            "    new.location_supplied,\n    untyped_coarse_location\n  ) then",
            1,
        )
        self.assertNotEqual(contents, mutated)
        self.assertTrue(repository_hash_contract_violations({MIGRATION: mutated}))

    def test_repository_hash_call_inventory_scans_other_script_tests(self):
        self.assertIn(OTHER_SCRIPT_TEST, repository_raw_sql_sources())
        contents = OTHER_SCRIPT_TEST.read_text(encoding="utf-8")
        injected = contents + """
        # raw SQL fixture with uncast first argument:
        # select public.compute_command_input_hash(
        #   ?, 1::smallint, 'command/v1'::text, 'algorithm/v1'::text,
        #   ?::uuid, '{}'::jsonb, false::boolean, null::jsonb
        # )
        """
        self.assertTrue(
            repository_hash_contract_violations({OTHER_SCRIPT_TEST: injected})
        )

    def test_actual_pg_completed_trip_fixture_satisfies_schedule_sealing_contract(self):
        source = compact_sql(ACTUAL_PG_TEST.read_text(encoding="utf-8"))
        self.assertIn("insert into public.trip_items", source)
        self.assertIn("planned_start_at, planned_end_at, stay_minutes", source)
        self.assertIn("stay_minutes, source, facts", source)
        self.assertIn(
            "'{\"location\":{\"lat\":33.0,\"lng\":126.0}}'::jsonb",
            source,
        )
        self.assertIn("'active', applied_at = now()", source)
        self.assertNotIn("active_schedule_version_id = (select id from activated)", source)
        self.assertNotIn("with activated as (", source)
        self.assertIn(
            "update public.trip_schedule_versions set status = 'active', applied_at = now() where id = ? and trip_plan_id = ?",
            source,
        )
        self.assertIn("connection.setautocommit(false)", source)
        self.assertIn(
            "update public.trip_plans set active_schedule_version_id = ? where id = ?",
            source,
        )
        self.assertIn("set constraints all immediate", source)
        self.assertIn(
            "p.active_schedule_version_id = ? and v.status = 'active' and v.applied_at is not null",
            source,
        )
        self.assertIn(
            'jdbc.update("update public.trip_plans set status = \'completed\' where id = ?", trip)',
            source,
        )

    def test_coarse_location_is_a_closed_union_without_raw_coordinates(self):
        migration = self.migration()
        for kind in ("grid_100m", "place", "stop"):
            self.assertIn(f"'{kind}'", migration)
        for field in ("gridx", "gridy", "placeid", "stopid"):
            self.assertIn(f"'{field}'", migration)
        self.assertIn("command_jsonb_object_size(coarse_location)", migration)
        self.assertNotIn("jsonb_object_length", migration)
        self.assertIn("chk_compute_run_inputs_location", migration)
        self.assertNotRegex(migration, r"\b(latitude|longitude|accuracy_meters|raw_location)\s+(double precision|numeric|integer|jsonb)")

    def test_expiry_only_shortens_through_restricted_transition_and_equality_is_due(self):
        migration = self.migration()
        self.assertIn("shorten_compute_run_input_location_expiry", migration)
        self.assertIn("interval '24 hours'", migration)
        self.assertIn("anchor_at <= evaluated_at", migration)
        self.assertIn("least(input_row.location_expires_at, candidate_expiry)", migration)
        self.assertIn("location_expires_at <= evaluated_at", migration)
        self.assertIn(
            "revoke all privileges on table public.compute_run_inputs from service_role",
            migration,
        )
        self.assertIn(
            "grant execute on function public.shorten_compute_run_input_location_expiry(uuid, timestamptz)",
            migration,
        )
        restricted = re.search(
            r"create function public\.shorten_compute_run_input_location_expiry\((.*?)\).*?as \$\$(.*?)\$\$;",
            migration,
        )
        self.assertIsNotNone(restricted)
        self.assertNotIn("terminal_at", restricted.group(1))
        self.assertNotIn("trip_ended_at", restricted.group(1))
        self.assertIn("compute_run_input_known_expiry", restricted.group(2))
        self.assertNotIn("location_redacted_at =", migration)

    def test_immutable_server_only_boundary_and_deployment_contracts_are_wired(self):
        migration = self.migration()
        self.assertIn("protect_compute_run_input_immutability", migration)
        self.assertIn("alter table public.compute_run_inputs enable row level security", migration)
        self.assertNotIn("create policy", migration)
        self.assertIn("revoke all on public.compute_run_inputs from anon", migration)
        self.assertIn("revoke all on public.compute_run_inputs from authenticated", migration)
        self.assertIn(
            "revoke all privileges on table public.compute_run_inputs from service_role",
            migration,
        )
        self.assertIn("grant select, insert on public.compute_run_inputs to service_role", migration)
        self.assertNotIn("grant select, insert, delete on public.compute_run_inputs", migration)
        schema_contract = (ROOT / "db/queries/schema_contract.sql").read_text(encoding="utf-8").lower()
        for privilege in ("select", "insert", "update", "delete", "truncate", "references", "trigger"):
            with self.subTest(privilege=privilege):
                self.assertIn(
                    f"has_table_privilege('service_role', 'public.compute_run_inputs', '{privilege}')",
                    schema_contract,
                )
        for path in (
            ROOT / "db/queries/schema_contract.sql",
            ROOT / "db/queries/smoke_check.sql",
            ROOT / "db/queries/database_negative_constraints.sql",
            ROOT / "db/README.md",
            ROOT / "docs/ARCHITECTURE.md",
            ROOT / "docs/designs/timing-jeju-db-schema-v0.md",
            ROOT / "docs/designs/timing-jeju-dbdiagram.dbml",
        ):
            with self.subTest(path=path.name):
                self.assertIn("compute_run_inputs", path.read_text(encoding="utf-8").lower())

    def test_negative_compute_input_fixture_is_self_contained_without_demo_seed(self):
        negative = compact_sql(NEGATIVE_CONSTRAINTS.read_text(encoding="utf-8"))
        section = negative.split("insert into public.compute_run_inputs", 1)[1]
        section = section.split(
            "select public.create_local_test_user( 'f1130000-0000-0000-0000-000000000001'",
            1,
        )[0]

        for demo_seed_id in (
            "09000000-0000-0000-0000-000000000001",
            "50000000-0000-0000-0000-000000000001",
            "60000000-0000-0000-0000-000000000001",
            "63000000-0000-0000-0000-000000000001",
            "63000000-0000-0000-0000-000000000002",
            "64000000-0000-0000-0000-000000000001",
        ):
            with self.subTest(demo_seed_id=demo_seed_id):
                self.assertNotIn(demo_seed_id, section)

        for local_fixture_id in (
            "f1600000-0000-0000-0000-000000000001",
            "f1610000-0000-0000-0000-000000000001",
            "f1630000-0000-0000-0000-000000000001",
            "f1640000-0000-0000-0000-000000000001",
        ):
            with self.subTest(local_fixture_id=local_fixture_id):
                self.assertIn(local_fixture_id, section)
        self.assertIn("schedule_revision_run_id", section)

    def test_scope_does_not_implement_intake_call_log_or_redaction_execution(self):
        migration = self.migration()
        for excluded in ("mcp_input_hash", "mcp_compute_call_logs", "controller", "location_redacted_at ="):
            with self.subTest(excluded=excluded):
                self.assertNotIn(excluded, migration)


if __name__ == "__main__":
    unittest.main()
