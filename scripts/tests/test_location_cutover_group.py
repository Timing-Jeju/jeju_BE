"""위치 정리의 두 불변 migration을 단일 실행 단위로 검증한다."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LocationCutoverGroupTest(unittest.TestCase):
    def load(self):
        spec = importlib.util.spec_from_file_location("location_cutover_group", ROOT / "scripts/location_cutover_group.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def minimal_root(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in (
            "supabase/migrations/manifest.json",
            "db/queries/canonical_migration_fingerprint.sql",
            "db/fingerprints/location_cutover_predecessors.json",
        ):
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        for filename, _ in self.load().SOURCES:
            target = root / "supabase/migrations" / filename
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / "supabase/migrations" / filename, target)
        return temporary, root

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

    def test_predecessor_policy_pins_canonical_query_and_reviewed_fingerprints(self):
        """선행 스키마 허용 목록은 검토한 query SHA와 서버 major별 fingerprint만 고정한다."""
        module = self.load()
        policy = module.load_predecessor_policy(ROOT)
        self.assertEqual(
            hashlib.sha256((ROOT / module.PREFLIGHT_POLICY).read_bytes()).hexdigest(),
            module.PREFLIGHT_POLICY_SHA256,
        )
        self.assertEqual(
            policy["canonicalQuerySha256"],
            "1ec0ac58bf7d45fc37c24e59936b19b2723049ac3fc85260d89c0e834bd81966",
        )
        self.assertEqual(
            policy["allowedFingerprints"],
            {
                "16": ["19745c65ef17192f09bfbb7d3167a3d1"],
                "17": [
                    "948a3dbda299b1b6621522b69c3167bb",
                    "f653e443df2891370dcb07d2ce36260e",
                ],
            },
        )

    def test_supabase_preflight_guards_ledger_shape_primary_key_and_server_fingerprint(self):
        """동일 transaction 사전 검증은 ledger 열·version PK와 서버별 선행 fingerprint를 닫아 둔다."""
        sql = self.load().render_supabase(ROOT)
        self.assertIn(b"version:text:NO", sql)
        self.assertIn(b"statements:ARRAY:YES", sql)
        self.assertIn(b"name:text:YES", sql)
        self.assertIn(b"location cutover migration ledger shape mismatch", sql)
        self.assertIn(b"location cutover predecessor schema fingerprint mismatch", sql)
        self.assertIn(b"current_setting('server_version_num')::integer / 10000", sql)
        self.assertIn(b"19745c65ef17192f09bfbb7d3167a3d1", sql)
        self.assertIn(b"948a3dbda299b1b6621522b69c3167bb", sql)
        self.assertIn(b"f653e443df2891370dcb07d2ce36260e", sql)
        self.assertIn(b"constraint_type = 'PRIMARY KEY'", sql)
        self.assertIn(b"array_agg(key_column.column_name::text", sql)

    def test_supabase_preflight_is_locked_and_runs_before_first_cutover_body(self):
        """선행 검증은 BEGIN 뒤 ledger 전용 잠금 안에서 첫 017 본문보다 먼저 끝난다."""
        sql = self.load().render_supabase(ROOT)
        begin = sql.index(b"begin;")
        ledger_lock = sql.index(b"lock table supabase_migrations.schema_migrations in access exclusive mode;")
        ledger_guard = sql.index(b"location cutover migration ledger shape mismatch")
        schema_guard = sql.index(b"location cutover predecessor schema fingerprint mismatch")
        preflight_end = sql.index(b"$preflight$;")
        first_body = sql.index(b"-- Issue #223:")
        self.assertLess(begin, ledger_lock)
        self.assertLess(ledger_lock, ledger_guard)
        self.assertLess(ledger_guard, schema_guard)
        self.assertLess(schema_guard, preflight_end)
        self.assertLess(preflight_end, first_body)
        self.assertNotIn(b"\\i ", sql)
        self.assertNotIn(b"\\ir ", sql)

    def test_changed_query_or_reviewed_policy_is_rejected_before_render(self):
        """canonical query나 검토 JSON이 바뀌면 Supabase SQL 생성을 즉시 거절한다."""
        module = self.load()
        temporary, root = self.minimal_root()
        self.addCleanup(temporary.cleanup)
        query = root / "db/queries/canonical_migration_fingerprint.sql"
        query.write_text(query.read_text() + "\n-- mutation\n")
        with self.assertRaisesRegex(ValueError, "canonical fingerprint query mismatch"):
            module.render_supabase(root)

        shutil.copy2(ROOT / "db/queries/canonical_migration_fingerprint.sql", query)
        policy_path = root / "db/fingerprints/location_cutover_predecessors.json"
        policy = json.loads(policy_path.read_text())
        policy["allowedFingerprints"]["17"].append("0" * 32)
        policy_path.write_text(json.dumps(policy))
        with self.assertRaisesRegex(ValueError, "predecessor fingerprint policy mismatch"):
            module.render_supabase(root)
