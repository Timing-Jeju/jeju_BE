from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260730030000_schedule_consistency_hardening.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME


def compact_sql(contents: str) -> str:
    return re.sub(r"\s+", " ", contents.lower()).strip()


class ScheduleConsistencyHardeningTest(unittest.TestCase):
    def migration(self) -> str:
        self.assertTrue(MIGRATION.is_file(), f"후속 일정 무결성 migration이 없습니다: {MIGRATION_NAME}")
        return compact_sql(MIGRATION.read_text(encoding="utf-8"))

    def function_definition(self, function_name: str) -> str:
        migration = self.migration()
        match = re.search(
            rf"create(?: or replace)? function public\.{function_name}\([^)]*\).*?as \$\$(.*?)\$\$;",
            migration,
        )
        self.assertIsNotNone(match, f"함수 정의가 없습니다: {function_name}")
        return match.group(1)

    def test_compose_applies_schedule_hardening_before_fixture(self):
        migration_mount = f"./supabase/migrations/{MIGRATION_NAME}"
        fixture_mount = "./db/local-postgres/seed_fixtures.sql"

        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            contents = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertIn(migration_mount, contents)
                self.assertLess(contents.index(migration_mount), contents.index(fixture_mount))

    def test_sealed_schedule_day_and_plan_dates_are_immutable(self):
        migration = self.migration()

        self.assertIn("protect_sealed_schedule_day", migration)
        self.assertIn("protect_sealed_trip_plan_dates", migration)
        self.assertRegex(
            migration,
            r"before insert or update of day_no, trip_date, start_time, end_time or delete",
        )
        self.assertIn("if tg_op = 'insert' then", migration)
        self.assertRegex(migration, r"before update of start_date, end_date")
        self.assertRegex(migration, r"status\s+in\s*\(\s*'candidate'\s*,\s*'active'\s*\)")
        self.assertIn("assert_schedule_day_item_windows", migration)
        self.assertIn("timezone('asia/seoul', item.planned_start_at)", migration)
        self.assertIn(
            "timezone('asia/seoul', item.planned_end_at)::date <> day.trip_date",
            migration,
        )

    def test_schedule_content_guard_locks_version_status(self):
        guard = self.function_definition("require_draft_schedule_version")

        self.assertGreaterEqual(guard.count("for share"), 3)
        self.assertNotIn("for key share", guard)

    def test_calendar_child_and_parent_date_changes_share_the_parent_lock(self):
        migration = self.migration()

        self.assertIn("create or replace function public.validate_trip_calendar_child", migration)
        self.assertIn(
            "perform public.lock_trip_plan_schedule_mutex(new.trip_plan_id)",
            self.function_definition("validate_trip_calendar_child"),
        )

    def test_sealing_and_calendar_mutations_share_plan_mutex_before_version_access(self):
        mutex = self.function_definition("lock_trip_plan_schedule_mutex")
        self.assertRegex(
            mutex,
            r"from public\.trip_plans p where p\.id = target_trip_plan_id for no key update",
        )

        calendar_child = self.function_definition("validate_trip_calendar_child")
        sealed_day = self.function_definition("protect_sealed_schedule_day")
        plan_dates = self.function_definition("protect_sealed_trip_plan_dates")
        sealing = self.function_definition("validate_schedule_version_sealing")
        base_lineage = self.function_definition("validate_schedule_version_base_lineage")

        for function_name, definition in (
            ("validate_trip_calendar_child", calendar_child),
            ("protect_sealed_schedule_day", sealed_day),
            ("protect_sealed_trip_plan_dates", plan_dates),
            ("validate_schedule_version_sealing", sealing),
            ("validate_schedule_version_base_lineage", base_lineage),
        ):
            with self.subTest(function=function_name):
                self.assertIn("perform public.lock_trip_plan_schedule_mutex", definition)

        self.assertLess(
            sealing.index("perform public.lock_trip_plan_schedule_mutex"),
            sealing.index("perform public.assert_schedule_version_sealable"),
        )
        self.assertLess(
            base_lineage.index("perform public.lock_trip_plan_schedule_mutex"),
            base_lineage.index("from public.trip_schedule_versions parent"),
        )
        self.assertNotIn("for share", base_lineage)

        for function_name, definition in (
            ("protect_sealed_schedule_day", sealed_day),
            ("protect_sealed_trip_plan_dates", plan_dates),
        ):
            with self.subTest(lock_order=function_name):
                self.assertLess(
                    definition.index("perform public.lock_trip_plan_schedule_mutex"),
                    definition.index("from public.trip_schedule_versions"),
                )
                self.assertNotIn("for share", definition)

    def test_schedule_base_lineage_must_point_to_an_earlier_version(self):
        migration = self.migration()

        self.assertIn("validate_schedule_version_base_lineage", migration)
        self.assertIn("parent_version_no >= new.version_no", migration)
        self.assertIn("schedule version number is immutable", migration)
        self.assertRegex(
            migration,
            r"before insert or update of base_schedule_version_id, version_no, trip_plan_id",
        )

    def test_day_scoped_results_use_composite_parent_keys(self):
        migration = self.migration()

        for unique_key in (
            "unique (id, schedule_version_id, trip_plan_id, trip_day_id)",
            "unique (id, trip_plan_id, schedule_version_id, trip_day_id)",
        ):
            with self.subTest(unique_key=unique_key):
                self.assertIn(unique_key, migration)

        for child_table in ("trip_weather_impacts", "recommendation_candidates"):
            with self.subTest(child_table=child_table):
                self.assertRegex(
                    migration,
                    rf"alter table public\.{child_table}[^;]+foreign key\s*\([^)]*trip_day_id[^)]*\)",
                )

        self.assertNotIn("alter column trip_day_id set not null", migration)
        self.assertIn("require_new_day_scoped_result", migration)
        self.assertIn("protect_legacy_null_day_result_parent", migration)
        self.assertIn("legacy null-day weather lineage is immutable", migration)
        self.assertIn("legacy null-day recommendation lineage is immutable", migration)
        day_guard = self.function_definition("require_new_day_scoped_result")
        self.assertIn("to_jsonb(old)", day_guard)
        self.assertIn("to_jsonb(new)", day_guard)
        self.assertNotRegex(day_guard, r"(?:old|new)\.(?:trip_item_id|trip_leg_id|base_item_id)")
        for trigger_name in (
            "trg_compute_runs_legacy_null_day_parent",
            "trg_trip_items_legacy_null_day_parent",
            "trg_trip_legs_legacy_null_day_parent",
        ):
            with self.subTest(trigger=trigger_name):
                self.assertIn(trigger_name, migration)

    def test_existing_sealed_schedules_are_audited_during_upgrade(self):
        migration = self.migration()

        self.assertIn("legacy sealed schedule failed integrity audit", migration)
        self.assertIn("perform public.assert_schedule_version_sealable", migration)
        self.assertIn("perform public.assert_schedule_day_coverage", migration)
        self.assertIn("perform public.assert_schedule_day_item_windows", migration)
        self.assertIn("legacy schedule base lineage is invalid", migration)
        self.assertIn("child_id=%s, parent_id=%s", migration)
        self.assertIn("child_version_no=%s, parent_version_no=%s", migration)
        self.assertIn("legacy day-scoped result failed same-day lineage audit", migration)
        self.assertIn("result_kind=%s, result_id=%s", migration)


if __name__ == "__main__":
    unittest.main()
