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
            "public.protect_transport_event_schedule_item_references()",
            "public.assert_schedule_version_sealable(uuid, uuid)",
            "public.validate_schedule_version_sealing()",
        )

        for signature in signatures:
            with self.subTest(signature=signature):
                self.assertIn(f"revoke all on function {signature} from public", migration)
                self.assertIn(f"revoke execute on function {signature} from anon", migration)
                self.assertIn(f"revoke execute on function {signature} from authenticated", migration)
                self.assertIn(f"grant execute on function {signature} to service_role", migration)


if __name__ == "__main__":
    unittest.main()
