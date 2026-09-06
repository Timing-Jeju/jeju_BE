import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = "20260915000000_jeju_timetable_route_scope.sql"
PROFILE_SLOT = "/docker-entrypoint-initdb.d/045_profile_image_storage.sql"
TIMETABLE_SLOT = "/docker-entrypoint-initdb.d/046_jeju_timetable_route_scope.sql"
SEED_SLOT = "/docker-entrypoint-initdb.d/099_seed_fixtures.sql"


class Issue38CurrentStackContractTest(unittest.TestCase):
    def test_timetable_migration_uses_slot_046_after_profile_and_before_seed(self) -> None:
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


if __name__ == "__main__":
    unittest.main()
