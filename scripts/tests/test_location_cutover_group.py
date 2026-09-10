"""위치 정리의 두 불변 migration을 단일 실행 단위로 검증한다."""
from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LocationCutoverGroupTest(unittest.TestCase):
    def load(self):
        spec = importlib.util.spec_from_file_location("location_cutover_group", ROOT / "scripts/location_cutover_group.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_generated_group_has_one_outer_transaction_and_exact_bodies(self):
        """두 원문 본문은 보존하고 최종 감사 뒤에만 커밋한다."""
        module = self.load()
        generated = module.render(ROOT)
        self.assertEqual(generated, (ROOT / module.OUTPUT).read_bytes())
        self.assertTrue(generated.startswith(b"-- Generated location cutover group; do not edit.\nbegin;\n"))
        self.assertTrue(generated.endswith(b"\ncommit;\n"))
        for filename, checksum in module.SOURCES:
            source = (ROOT / "supabase/migrations" / filename).read_bytes()
            self.assertIn(module.body(source, checksum), generated)

    def test_changed_source_or_unexpected_envelope_is_rejected(self):
        """검증한 원문과 transaction 외곽이 달라지면 생성부터 거절한다."""
        module = self.load()
        filename, checksum = module.SOURCES[0]
        original = (ROOT / "supabase/migrations" / filename).read_bytes()
        for changed in (original + b"\n", original.replace(b"begin;", b"BEGIN;", 1), b"\xef\xbb\xbf" + original):
            with self.subTest(size=len(changed)), self.assertRaises(ValueError):
                module.body(changed, checksum)

    def test_default_init_does_not_execute_group_members_separately(self):
        """기본 Docker와 Java 초기화는 원문 두 개의 개별 실행을 피한다."""
        for name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            source = (ROOT / name).read_text()
            self.assertIn("./db/local-postgres/20260918000017_location_cutover_group.sql:/docker-entrypoint-initdb.d/055_location_cutover_group.sql:ro", source)
            for filename, _ in self.load().SOURCES:
                self.assertNotIn("./supabase/migrations/" + filename + ":/docker-entrypoint-initdb.d/", source)
        factory = (ROOT / "services/spring-api/src/test/java/com/timingjeju/api/support/postgresql/PostgreSqlTestContainerFactory.java").read_text()
        self.assertIn('name.equals("20260918000018_revision_request_hash_audit.sql")', factory)
        self.assertIn('db/local-postgres/20260918000017_location_cutover_group.sql', factory)

    def test_supabase_history_is_recorded_inside_the_group_after_final_audit(self):
        """실제 적용 SQL은 최종 감사 이후 같은 transaction에서 두 이력을 등록한다."""
        module = self.load()
        sql = module.render_supabase(ROOT)
        self.assertEqual(sql, (ROOT / "db/local-postgres/location_cutover_supabase.sql").read_bytes())
        self.assertTrue(sql.startswith(b"begin;\n"))
        self.assertTrue(sql.endswith(b"commit;\n"))
        self.assertLess(sql.index(b"lock table supabase_migrations.schema_migrations"), sql.index(b"-- Issue #223:"))
        self.assertGreater(sql.index(b"insert into supabase_migrations.schema_migrations"), sql.index(b"select '20260918000018'::text"))
        self.assertIn(b"location cutover migration history mismatch", sql)
