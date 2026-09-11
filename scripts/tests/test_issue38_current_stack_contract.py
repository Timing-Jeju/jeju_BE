import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = "20260918000009_jeju_timetable_route_scope.sql"
PROFILE_SLOT = "/docker-entrypoint-initdb.d/044_profile_image_storage.sql"
TIMETABLE_SLOT = "/docker-entrypoint-initdb.d/047_jeju_timetable_route_scope.sql"
SEED_SLOT = "/docker-entrypoint-initdb.d/099_seed_fixtures.sql"


class Issue38CurrentStackContractTest(unittest.TestCase):
    def test_timetable_migration_uses_slot_047_after_profile_and_before_seed(self) -> None:
        self.assertTrue((ROOT / "supabase/migrations" / MIGRATION).is_file())
        mount = (
            f"./supabase/migrations/{MIGRATION}:"
            f"{TIMETABLE_SLOT}:ro"
        )
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            contents = (ROOT / compose_name).read_text()
            self.assertEqual(1, contents.count(mount), compose_name)
            self.assertLess(contents.index(PROFILE_SLOT), contents.index(TIMETABLE_SLOT))
            self.assertLess(contents.index(TIMETABLE_SLOT), contents.index(SEED_SLOT))
            self.assertNotIn("/045_jeju_timetable_route_scope.sql", contents)

        smoke = (ROOT / "scripts/docker-smoke-test.sh").read_text()
        self.assertEqual(2, smoke.count(TIMETABLE_SLOT))
        self.assertNotIn("/045_jeju_timetable_route_scope.sql", smoke)

    def test_importer_and_runner_contract_is_present_without_public_api(self) -> None:
        application = ROOT / "services/spring-api/src/main/java/com/timingjeju/api/application/timetable"
        adapter = ROOT / "services/spring-api/src/main/java/com/timingjeju/api/global/timetable"
        for path in (
            application / "JejuTimetableImportService.java",
            application / "TimetableImportCommand.java",
            adapter / "JejuTimetableXlsxParser.java",
            adapter / "JejuTimetableImportRunnerConfiguration.java",
            adapter / "SafeTimetableFileReader.java",
        ):
            self.assertTrue(path.is_file(), path)

        runner = (adapter / "JejuTimetableImportRunnerConfiguration.java").read_text()
        self.assertIn('havingValue = "true"', runner)
        self.assertIn('"timing-jeju.timetable-import.dry-run", Boolean.class, true', runner)
        self.assertIn("requiredAbsolutePath", runner)
        self.assertFalse(any(application.rglob("*Controller.java")))
        self.assertFalse(any(adapter.rglob("*Controller.java")))

    def test_official_shape_allowlist_and_synthetic_fixture_boundary_are_pinned(self) -> None:
        schema = (ROOT / "fixtures/jeju-timetable/operator-mapping-v1.schema.json").read_text()
        readme = (ROOT / "fixtures/jeju-timetable/README.md").read_text()
        migration = (ROOT / "supabase/migrations" / MIGRATION).read_text()

        for expected in ("3043887", "405001", "405009"):
            self.assertIn(expected, schema)
        self.assertIn('"routeSourceProvider": {"const": "TAGO"}', schema)
        self.assertIn('"routeCityCode": {"const": "39"}', schema)
        self.assertIn("JEJU_PROVINCE", readme)
        self.assertIn("synthetic", readme)
        self.assertIn("IGNORE_UNRESOLVED", readme)
        self.assertIn("JEJU_PROVINCE", migration)

    def test_manifest_prefix_expression_and_legacy_backfill_are_fail_closed(self) -> None:
        migration = (ROOT / "supabase/migrations" / MIGRATION).read_text()
        self.assertIn("'3043887/' || (payload->>'scheduleId') || '/'", migration)
        self.assertNotIn("'3043887/' || payload->>'scheduleId' || '/'", migration)
        self.assertIn("old.route_source_provider is null", migration)
        self.assertIn(
            "new.route_source_provider is not distinct from old.source_provider",
            migration,
        )
        self.assertIn(
            "new.route_city_code is not distinct from old.city_code", migration
        )
        self.assertIn(
            "drop trigger trg_timetable_source_lineage on public.timetable_entries",
            migration,
        )
        self.assertIn(
            "execute function public.validate_timetable_route_scope_backfill_lineage()",
            migration,
        )
        self.assertIn(
            "execute function public.validate_normalized_source_lineage()", migration
        )
        self.assertLess(
            migration.index("drop trigger trg_timetable_source_lineage"),
            migration.index("update public.timetable_entries"),
        )
        self.assertGreater(
            migration.rindex("create constraint trigger trg_timetable_source_lineage"),
            migration.index("update public.timetable_entries"),
        )
        self.assertNotIn("delete from public.timetable_entries", migration.lower())

    def test_canonical_seed_supplies_explicit_timetable_route_reference_scope(self) -> None:
        seed = (ROOT / "db/local-postgres/seed_fixtures.sql").read_text()
        timetable_insert = re.search(
            r"insert into timetable_entries \((?P<columns>.*?)\) values(?P<values>.*?);",
            seed,
            re.DOTALL,
        )

        self.assertIsNotNone(timetable_insert)
        assert timetable_insert is not None
        self.assertIn("route_source_provider", timetable_insert.group("columns"))
        self.assertIn("route_city_code", timetable_insert.group("columns"))
        self.assertEqual(3, timetable_insert.group("values").count("'TAGO', '39'"))

    def test_legacy_upgrade_fixture_keeps_oversized_transport_boundaries(self) -> None:
        fixture = (ROOT / "db/queries/legacy_v1_upgrade_fixture.sql").read_text()

        self.assertIn("'e3500000-0000-0000-0000-000000000010'", fixture)
        self.assertIn("'e3500000-0000-0000-0000-000000000011'", fixture)
        self.assertIn("'e3300000-0000-0000-0000-000000000010'", fixture)
        self.assertIn("'e3400000-0000-0000-0000-000000000010'", fixture)

    def test_negative_contract_timetable_fixtures_supply_route_reference_scope(self) -> None:
        contract = (ROOT / "db/queries/database_negative_constraints.sql").read_text()
        timetable_section = contract[
            contract.index("insert into timetable_entries") : contract.index(
                "insert into app_sessions"
            )
        ]
        inserts = re.findall(
            r"insert into timetable_entries \((?P<columns>.*?)\) values",
            timetable_section,
            re.DOTALL,
        )

        self.assertEqual(7, len(inserts))
        for columns in inserts:
            self.assertIn("route_source_provider", columns)
            self.assertIn("route_city_code", columns)

    def test_legacy_upgrade_contract_accepts_both_strict_route_scope_sqlstates(self) -> None:
        contract = (ROOT / "db/queries/legacy_v1_upgrade_contract.sql").read_text()

        self.assertIn("when check_violation or not_null_violation then null", contract)
        self.assertIn("route_source_provider = 'TAGO'", contract)
        self.assertIn("route_city_code = '39'", contract)


if __name__ == "__main__":
    unittest.main()
