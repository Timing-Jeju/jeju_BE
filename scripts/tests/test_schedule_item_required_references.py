from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260907000001_schedule_item_required_references.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME


def compact_sql(contents: str) -> str:
    return re.sub(r"\s+", " ", contents.lower()).strip()


class ScheduleItemRequiredReferencesTest(unittest.TestCase):
    def migration(self) -> str:
        """일정 항목 필수 참조 보정 migration을 정규화해 읽는다."""
        self.assertTrue(MIGRATION.is_file(), f"append-only migration이 없습니다: {MIGRATION_NAME}")
        return compact_sql(MIGRATION.read_text(encoding="utf-8"))

    def test_append_only_migration_uses_slot_038_before_seed_everywhere(self) -> None:
        """필수 참조 보정은 기존 생성 계약 뒤 038 슬롯에서 seed 전에 실행된다."""
        source = f"./supabase/migrations/{MIGRATION_NAME}"
        previous = "/docker-entrypoint-initdb.d/037_schedule_item_create_contract.sql"
        target = "/docker-entrypoint-initdb.d/038_schedule_item_required_references.sql"
        seed = "/docker-entrypoint-initdb.d/099_seed_fixtures.sql"

        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertEqual(1, compose.count(f"{source}:{target}:ro"))
                self.assertLess(compose.index(previous), compose.index(target))
                self.assertLess(compose.index(target), compose.index(seed))

        smoke = (ROOT / "scripts/docker-smoke-test.sh").read_text(encoding="utf-8")
        self.assertEqual(2, smoke.count(target))

    def test_legacy_rows_are_audited_before_required_reference_check(self) -> None:
        """기존 typed item의 누락·오염 참조는 CHECK 설치 전에 식별 가능한 오류로 중단된다."""
        migration = self.migration()
        audit = "legacy schedule item required reference audit failed"
        check = "add constraint chk_trip_items_required_references"

        self.assertIn(audit, migration)
        self.assertIn("item_id=%s", migration)
        self.assertIn("item_type=%s", migration)
        self.assertLess(migration.index(audit), migration.index(check))
        self.assertIn("item_type = 'accommodation' and accommodation_id is not null", migration)
        self.assertIn(
            "item.item_type in ('arrival', 'departure') and item.accommodation_id is null "
            "and item.transport_event_id is not null",
            migration,
        )
        self.assertIn("event.event_type = item.item_type", migration)

    def test_check_and_trigger_enforce_type_consistency_and_trip_ownership(self) -> None:
        """새 일정 항목은 유형별 필수 참조·상호 배타성과 동일 여행 소유를 모두 지킨다."""
        migration = self.migration()

        self.assertIn("add constraint chk_trip_items_required_references check", migration)
        self.assertIn("item_type = 'accommodation'", migration)
        self.assertIn("item_type in ('arrival', 'departure')", migration)
        self.assertIn("item_type not in ('accommodation', 'arrival', 'departure')", migration)
        self.assertIn("accommodation_id is null", migration)
        self.assertIn("transport_event_id is null", migration)
        self.assertIn("create function public.validate_trip_item_required_references()", migration)
        self.assertIn("event.trip_plan_id = new.trip_plan_id", migration)
        self.assertIn("event.event_type = new.item_type", migration)
        self.assertIn("accommodation.trip_plan_id = new.trip_plan_id", migration)
        self.assertIn("trg_trip_items_required_references", migration)

    def test_transport_event_type_is_an_atomic_composite_foreign_key(self) -> None:
        """교통 이벤트 유형과 item 유형은 동시 쓰기에도 깨지지 않는 복합 FK로 묶인다."""
        migration = self.migration()

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

        self.assertIn("create function public.assert_schedule_version_sealable", migration)
        self.assertIn("perform public.assert_schedule_item_required_references", migration)
        self.assertIn("target_schedule_version_id", migration)
        self.assertIn("target_trip_plan_id", migration)

    def test_assertion_and_trigger_helpers_are_not_client_executable(self) -> None:
        """public assertion과 trigger helper는 클라이언트 역할에 EXECUTE를 노출하지 않는다."""
        migration = self.migration()
        signatures = (
            "public.assert_schedule_version_core_sealable(uuid, uuid)",
            "public.assert_schedule_item_required_references(uuid, uuid)",
            "public.validate_trip_item_required_references()",
            "public.assert_schedule_version_sealable(uuid, uuid)",
            "public.validate_schedule_version_sealing()",
        )

        for signature in signatures:
            with self.subTest(signature=signature):
                self.assertIn(f"revoke all on function {signature} from public", migration)
                self.assertIn(f"revoke execute on function {signature} from anon", migration)
                self.assertIn(f"revoke execute on function {signature} from authenticated", migration)
                self.assertIn(f"grant execute on function {signature} to service_role", migration)

    def test_local_seed_populates_required_schedule_item_references(self) -> None:
        """038 뒤에 실행되는 로컬 seed도 typed item의 필수 참조를 명시한다."""
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
