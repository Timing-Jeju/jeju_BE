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
        migration = self.migration()

        self.assertIn("create or replace function public.require_draft_schedule_version", migration)
        self.assertGreaterEqual(migration.count("for share"), 4)
        self.assertNotIn("for key share", migration)

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


if __name__ == "__main__":
    unittest.main()
