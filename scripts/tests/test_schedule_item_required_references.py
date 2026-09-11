from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260918000008_schedule_item_required_references_correction.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME
BASELINE = (
    ROOT
    / "supabase"
    / "migrations"
    / "20260918000007_schedule_item_required_references.sql"
)


def compact_sql(contents: str) -> str:
    return re.sub(r"\s+", " ", contents.lower()).strip()


class ScheduleItemRequiredReferencesTest(unittest.TestCase):
    def migration(self) -> str:
        """일정 항목 필수 참조 보정 migration을 정규화해 읽는다."""
        self.assertTrue(MIGRATION.is_file(), f"append-only migration이 없습니다: {MIGRATION_NAME}")
        return compact_sql(MIGRATION.read_text(encoding="utf-8"))

    @staticmethod
    def section(migration: str, start: str, end: str) -> str:
        start_index = migration.index(start)
        return migration[start_index:migration.index(end, start_index)]

    def assert_current_reference_predicate(self, section: str, qualifier: str) -> None:
        item = f"{qualifier}." if qualifier else ""
        self.assertIn(
            f"{item}item_type = 'place_visit' and {item}place_id is not null "
            f"and {item}accommodation_id is null and {item}transport_event_id is null",
            section,
        )
        self.assertIn(
            f"{item}item_type = 'accommodation' and {item}accommodation_id is not null "
            f"and {item}transport_event_id is null",
            section,
        )
        self.assertIn(
            f"{item}item_type in ('arrival', 'departure') and {item}accommodation_id is null "
            f"and {item}transport_event_id is not null",
            section,
        )
        self.assertIn(
            f"{item}item_type in ('meal', 'free_time', 'custom') and "
            f"{item}accommodation_id is null and {item}transport_event_id is null "
            f"and {item}title is not null and translate( {item}title,",
            section,
        )
        self.assertIn("u&'\\0009\\000a", section)
        self.assertIn("<> ''", section)
        self.assertNotIn("item_type not in ('accommodation', 'arrival', 'departure')", section)

    def test_current_canonical_predicate_is_repeated_at_all_four_boundaries(self) -> None:
        """audit/check/trigger/sealing은 같은 4-boundary predicate를 각각 명시한다."""
        migration = self.migration()
        audit = self.section(migration, "do $$", "drop trigger trg_trip_items_required_references")
        check = self.section(
            migration,
            "add constraint chk_trip_items_required_references check",
            "create or replace function public.validate_trip_item_required_references()",
        )
        trigger = self.section(
            migration,
            "create or replace function public.validate_trip_item_required_references()",
            "create trigger trg_trip_items_required_references",
        )
        sealing = self.section(
            migration,
            "create or replace function public.assert_schedule_item_required_references(",
            "revoke all on function public.validate_trip_item_required_references()",
        )

        for name, boundary, qualifier in (
            ("legacy-audit", audit, "item"),
            ("check", check, ""),
            ("trigger", trigger, "new"),
            ("sealing", sealing, "item"),
        ):
            with self.subTest(boundary=name):
                self.assert_current_reference_predicate(boundary, qualifier)

    def test_baseline_and_correction_use_slots_045_and_046_before_seed(self) -> None:
        """#50 기준선과 #51 보정은 생성 계약 뒤 별도 슬롯으로 seed 전에 실행된다."""
        source = f"./supabase/migrations/{MIGRATION_NAME}"
        previous = "/docker-entrypoint-initdb.d/037_schedule_item_create_contract.sql"
        baseline = "/docker-entrypoint-initdb.d/045_schedule_item_required_references.sql"
        target = "/docker-entrypoint-initdb.d/046_schedule_item_required_references_correction.sql"
        seed = "/docker-entrypoint-initdb.d/099_seed_fixtures.sql"

        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertEqual(1, compose.count(f"{source}:{target}:ro"))
                self.assertLess(compose.index(previous), compose.index(baseline))
                self.assertLess(compose.index(baseline), compose.index(target))
                self.assertLess(compose.index(target), compose.index(seed))

        smoke = (ROOT / "scripts/docker-smoke-test.sh").read_text(encoding="utf-8")
        self.assertEqual(3, smoke.count(target))

    def test_legacy_rows_are_audited_before_required_reference_check(self) -> None:
        """기존 typed item의 누락·오염 참조는 CHECK 설치 전에 식별 가능한 오류로 중단된다."""
        migration = self.migration()
        legacy = self.section(migration, "do $$", "drop trigger trg_trip_items_required_references")
        audit = "legacy schedule item required reference audit failed"
        check = "add constraint chk_trip_items_required_references"

        self.assertIn(audit, legacy)
        self.assertIn("item_id=%s", legacy)
        self.assertIn("item_type=%s", legacy)
        self.assertIn("trip_plan_id=%s", legacy)
        self.assertIn("invalid_fields=%s", legacy)
        self.assertLess(migration.index(audit), migration.index(check))
        self.assert_current_reference_predicate(legacy, "item")
        self.assertIn(
            "accommodation.id = item.accommodation_id and "
            "accommodation.trip_plan_id = item.trip_plan_id",
            legacy,
        )
        self.assertIn(
            "event.id = item.transport_event_id and event.trip_plan_id = item.trip_plan_id",
            legacy,
        )
        self.assertIn("item.item_type = 'accommodation' and accommodation.id is null", legacy)
        self.assertIn("event.id is null or event.event_type <> item.item_type", legacy)

    def test_check_and_trigger_enforce_type_consistency_and_trip_ownership(self) -> None:
        """새 일정 항목은 유형별 필수 참조·상호 배타성과 동일 여행 소유를 모두 지킨다."""
        migration = self.migration()
        check = self.section(
            migration,
            "add constraint chk_trip_items_required_references check",
            "create or replace function public.validate_trip_item_required_references()",
        )
        trigger = self.section(
            migration,
            "create or replace function public.validate_trip_item_required_references()",
            "create trigger trg_trip_items_required_references",
        )

        self.assert_current_reference_predicate(check, "")
        self.assert_current_reference_predicate(trigger, "new")
        self.assertIn("if not (", trigger)
        self.assertIn("accommodation.id = new.accommodation_id", trigger)
        self.assertIn("accommodation.trip_plan_id = new.trip_plan_id", trigger)
        self.assertIn("event.id = new.transport_event_id", trigger)
        self.assertIn("event.trip_plan_id = new.trip_plan_id", trigger)
        self.assertIn("event.event_type = new.item_type", trigger)
        self.assertIn("if not exists", trigger)
        self.assertIn(
            "before insert or update of item_type, trip_plan_id, place_id, accommodation_id, "
            "transport_event_id, title",
            migration,
        )

    def test_transport_event_type_is_an_atomic_composite_foreign_key(self) -> None:
        """교통 이벤트 유형과 item 유형은 동시 쓰기에도 깨지지 않는 복합 FK로 묶인다."""
        migration = compact_sql(BASELINE.read_text(encoding="utf-8"))

        self.assertIn(
            "add constraint uq_trip_transport_events_id_plan_event_type "
            "unique (id, trip_plan_id, event_type)",
            migration,
        )
        self.assertIn(
            "foreign key (transport_event_id, trip_plan_id, item_type) "
            "references public.trip_transport_events (id, trip_plan_id, event_type)",
            migration,
        )
        self.assertIn(
            "on public.trip_items (transport_event_id, trip_plan_id, item_type)",
            migration,
        )

    def test_docker_contract_races_item_insert_against_event_type_update(self) -> None:
        """Docker 계약은 item insert와 event type 변경의 실제 두 세션 경합을 검증한다."""
        concurrency = compact_sql(
            (ROOT / "db/queries/database_concurrency_contract.sql").read_text(
                encoding="utf-8"
            )
        )

        for connection in ("schedule_reference_a", "schedule_reference_b"):
            self.assertIn(f"dblink_connect( '{connection}'", concurrency)
            self.assertIn(f"dblink_disconnect('{connection}')", concurrency)
        self.assertIn(
            "assert_connection_is_blocked( 'schedule_reference', 'a', 'b', "
            "'schedule_reference_b' )",
            concurrency,
        )
        self.assertIn("transport event type writer must return 23503", concurrency)
        self.assertIn("schedule item transport reference mismatch count is not zero", concurrency)

    def test_sealing_assertion_rechecks_required_references(self) -> None:
        """CHECK를 우회한 legacy 행도 candidate·active 봉인 시 공용 assertion에서 거부된다."""
        migration = self.migration()
        full_migration = compact_sql(BASELINE.read_text(encoding="utf-8")) + " " + migration
        sealing = self.section(
            migration,
            "create or replace function public.assert_schedule_item_required_references(",
            "revoke all on function public.validate_trip_item_required_references()",
        )

        self.assertIn("create function public.assert_schedule_version_sealable", full_migration)
        self.assertIn("perform public.assert_schedule_item_required_references", full_migration)
        self.assert_current_reference_predicate(sealing, "item")
        self.assertIn("item.schedule_version_id = target_schedule_version_id", sealing)
        self.assertIn("item.trip_plan_id = target_trip_plan_id", sealing)
        self.assertIn(
            "accommodation.id = item.accommodation_id and "
            "accommodation.trip_plan_id = item.trip_plan_id",
            sealing,
        )
        self.assertIn(
            "event.id = item.transport_event_id and event.trip_plan_id = item.trip_plan_id",
            sealing,
        )
        self.assertIn("item.item_type = 'accommodation' and accommodation.id is null", sealing)
        self.assertIn("event.id is null or event.event_type <> item.item_type", sealing)

    def test_assertion_and_trigger_helpers_are_not_client_executable(self) -> None:
        """public assertion과 trigger helper는 클라이언트 역할에 EXECUTE를 노출하지 않는다."""
        migration = compact_sql(BASELINE.read_text(encoding="utf-8")) + " " + self.migration()
        service_role_signatures = (
            "public.assert_schedule_version_core_sealable(uuid, uuid)",
            "public.assert_schedule_item_required_references(uuid, uuid)",
            "public.assert_schedule_version_sealable(uuid, uuid)",
            "public.validate_schedule_version_sealing()",
        )

        for signature in service_role_signatures:
            with self.subTest(signature=signature):
                self.assertIn(f"revoke all on function {signature} from public", migration)
                self.assertIn(f"revoke execute on function {signature} from anon", migration)
                self.assertIn(f"revoke execute on function {signature} from authenticated", migration)
                self.assertIn(f"grant execute on function {signature} to service_role", migration)

        trigger_signature = "public.validate_trip_item_required_references()"
        self.assertIn(f"revoke all on function {trigger_signature} from public", migration)
        self.assertIn(f"revoke execute on function {trigger_signature} from anon", migration)
        self.assertIn(f"revoke execute on function {trigger_signature} from authenticated", migration)
        self.assertIn(f"revoke execute on function {trigger_signature} from service_role", migration)
        self.assertNotIn(f"grant execute on function {trigger_signature} to service_role", migration)

    def test_local_seed_populates_required_schedule_item_references(self) -> None:
        """046 뒤에 실행되는 로컬 seed도 typed item의 필수 참조를 명시한다."""
        seed = compact_sql(
            (ROOT / "db/local-postgres/seed_fixtures.sql").read_text(encoding="utf-8")
        )

        self.assertIn(
            "facts, accommodation_id, transport_event_id ) values",
            seed,
        )
        self.assertIn(
            "'arrival', '20000000-0000-0000-0000-000000000001', '제주 도착'",
            seed,
        )
        self.assertIn("null, '50100000-0000-0000-0000-000000000001')", seed)
        self.assertIn(
            "'departure', '20000000-0000-0000-0000-000000000001', '제주 출발'",
            seed,
        )
        self.assertIn("null, '50100000-0000-0000-0000-000000000002')", seed)
        self.assertIn("'50200000-0000-0000-0000-000000000001', null)", seed)
        self.assertIn("'50200000-0000-0000-0000-000000000002', null)", seed)


if __name__ == "__main__":
    unittest.main()
