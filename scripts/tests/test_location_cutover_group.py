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

    def test_raw_supabase_sequential_migrations_are_disabled(self):
        """017·018을 개별 commit하는 기본 CLI 경로는 설정 단계에서 닫힌다."""
        config = (ROOT / "supabase/config.toml").read_text()
        migrations = config.split("[db.migrations]", 1)[1].split("[db.seed]", 1)[0]
        self.assertIn("enabled = false", migrations)
        smoke = (ROOT / "scripts/supabase-smoke-test.sh").read_text()
        self.assertIn("검증된 017+018 단일 transaction runner 전에는", smoke)
        self.assertLess(smoke.index("exit 64"), smoke.index('"$SUPABASE_BIN" start'))

    def test_forward_remediation_uses_reserved_safe_chronology(self):
        """#242의 019/057 예약을 건드리지 않고 020/058을 사용한다."""
        migration = ROOT / "supabase/migrations/20260918000020_location_provenance_fail_closed.sql"
        self.assertTrue(migration.is_file())
        manifest = (ROOT / "supabase/migrations/manifest.json").read_text()
        self.assertIn("20260918000020_location_provenance_fail_closed.sql", manifest)
        self.assertIn('"initSlot":"058"', manifest)

    def test_supabase_ledger_rows_match_cli_shape(self):
        """그룹 적용 이력은 CLI 2.116.0의 version/name/statements shape를 채운다."""
        sql = self.load().render_supabase(ROOT)
        self.assertIn(b"schema_migrations(version, name, statements)", sql)
        self.assertIn(b"'user_location_write_guard_purge'", sql)
        self.assertIn(b"'revision_request_hash_audit'", sql)
        self.assertNotIn(b"schema_migrations(version)\nvalues", sql)
