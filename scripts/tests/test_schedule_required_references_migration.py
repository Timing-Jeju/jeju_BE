import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "supabase/migrations/20260918000008_schedule_item_required_references_correction.sql"
NEGATIVE = ROOT / "db/queries/database_negative_constraints.sql"
LEGACY_FIXTURE = ROOT / "db/queries/legacy_schedule_item_reference_conflict_fixture.sql"


class ScheduleRequiredReferencesMigrationTest(unittest.TestCase):
    def test_audit_check_trigger_and_sealing_helper_share_canonical_predicate(self) -> None:
        sql = re.sub(r"\s+", " ", MIGRATION.read_text(encoding="utf-8").lower())
        normalized = sql.replace("item.", "").replace("new.", "")
        normalized = normalized.replace("( ", "(").replace(" )", ")")
        normalized = re.sub(r"translate\(\s+", "translate(", normalized)
        normalized = re.sub(r",\s*''\s*\)", ", '')", normalized)
        java_blank = (
            "translate(title, "
            "u&'\\0009\\000a\\000b\\000c\\000d\\001c\\001d\\001e\\001f"
            "\\0020\\1680\\2000\\2001\\2002\\2003\\2004\\2005\\2006"
            "\\2008\\2009\\200a\\2028\\2029\\205f\\3000', '') <> ''"
        )
        branches = (
            "(item_type = 'place_visit' and place_id is not null and accommodation_id is null and transport_event_id is null)",
            "(item_type = 'accommodation' and accommodation_id is not null and transport_event_id is null)",
            "(item_type in ('arrival', 'departure') and accommodation_id is null and transport_event_id is not null)",
            "(item_type in ('meal', 'free_time', 'custom') and accommodation_id is null and transport_event_id is null and title is not null and "
            + java_blank
            + ")",
        )
        for branch in branches:
            with self.subTest(branch=branch):
                self.assertEqual(4, normalized.count(branch))
        self.assertIn(
            "before insert or update of item_type, trip_plan_id, place_id, accommodation_id, transport_event_id, title",
            sql,
        )

    def test_legacy_audit_detail_never_contains_user_title_or_reference_values(self) -> None:
        sql = MIGRATION.read_text(encoding="utf-8").lower()
        audit = sql[: sql.index("drop trigger trg_trip_items_required_references")]
        self.assertNotIn("invalid_item.title", audit)
        self.assertNotIn("title=%s", audit)
        self.assertNotIn("place_id=%s", audit)
        self.assertIn("invalid_fields=%s", audit)
        self.assertIn("required_reference_contract", audit)

    def test_each_item_type_has_a_negative_fixture_and_helper_sealing_are_regressed(self) -> None:
        negative = NEGATIVE.read_text(encoding="utf-8")
        for scenario in (
            "place_visit requires place reference",
            "meal requires nonblank title",
            "accommodation item requires accommodation reference",
            "arrival item requires transport event reference",
            "departure item requires transport event reference",
            "free_time requires nonblank title",
            "custom requires nonblank title",
            "meal rejects tab-only title",
            "free_time rejects newline-only title",
            "direct helper rejects invalid required reference",
            "sealed schedule rejects invalid required reference",
        ):
            with self.subTest(scenario=scenario):
                self.assertIn(scenario, negative)

    def test_legacy_audit_fixture_exercises_a_canonical_invalid_row(self) -> None:
        fixture = LEGACY_FIXTURE.read_text(encoding="utf-8")
        self.assertIn("legacy audit rejects invalid required reference", fixture)
        self.assertIn("item_type = 'custom'", fixture)
        self.assertIn("title = E'\\n'", fixture)


if __name__ == "__main__":
    unittest.main()
