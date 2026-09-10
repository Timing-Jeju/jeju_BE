"""위치 정리와 후속 hash 정책 migration을 안전한 실행 단위로 검증한다."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ATOMIC_MIGRATIONS = ROOT / "supabase/atomic-migrations"


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
            source = (ATOMIC_MIGRATIONS / filename).read_bytes()
            self.assertIn(module.body(source, checksum), generated)

    def test_changed_source_or_unexpected_envelope_is_rejected(self):
        """검증한 원문과 transaction 외곽이 달라지면 생성부터 거절한다."""
        module = self.load()
        filename, checksum = module.SOURCES[0]
        original = (ATOMIC_MIGRATIONS / filename).read_bytes()
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
        """실제 적용 SQL은 최종 감사 이후 같은 transaction에서 전체 이력을 등록한다."""
        module = self.load()
        sql = module.render_supabase(ROOT)
        self.assertEqual(sql, (ROOT / "db/local-postgres/location_cutover_supabase.sql").read_bytes())
        self.assertTrue(sql.startswith(b"begin;\n"))
        self.assertTrue(sql.endswith(b"commit;\n"))
        self.assertLess(sql.index(b"lock table supabase_migrations.schema_migrations"), sql.index(b"-- Issue #223:"))
        self.assertGreater(sql.index(b"insert into supabase_migrations.schema_migrations"), sql.index(b"select '20260918000018'::text"))
        self.assertIn(b"location cutover migration history mismatch", sql)
        self.assertIn(b"-- Issue #223 post-merge remediation", sql)
        self.assertIn(b"'20260918000019', 'planned_route_request_hash_policy'", sql)
        self.assertIn(b"'20260918000020', 'location_provenance_fail_closed'", sql)
        self.assertLess(
            sql.index(b"'20260918000019', 'planned_route_request_hash_policy'"),
            sql.index(b"'20260918000020', 'location_provenance_fail_closed'"),
        )

    def test_default_smoke_refuses_raw_sequential_migrations(self):
        """config는 보조층이고 기본 smoke는 017·018 순차 실행 전에 거부한다."""
        config = (ROOT / "supabase/config.toml").read_text()
        migrations = config.split("[db.migrations]", 1)[1].split("[db.seed]", 1)[0]
        self.assertIn("enabled = false", migrations)
        smoke = (ROOT / "scripts/supabase-smoke-test.sh").read_text()
        self.assertIn("검증된 017+018 단일 transaction runner 전에는", smoke)
        self.assertLess(smoke.index("exit 64"), smoke.index('"$SUPABASE_BIN" start'))

    def test_forward_remediation_uses_reserved_safe_chronology(self):
        """#242의 019/057 예약을 건드리지 않고 020/058을 사용한다."""
        migration = ATOMIC_MIGRATIONS / "20260918000020_location_provenance_fail_closed.sql"
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
        self.assertIn(b"'location_provenance_fail_closed'", sql)
        self.assertNotIn(b"schema_migrations(version)\nvalues", sql)

    def test_cli_2116_fetch_reconstructs_exact_replayable_sources(self):
        """CLI fetch의 statements.join(';\\n')+';\\n' 결과가 원문 SHA와 같다."""
        module = self.load()
        sql = module.render_supabase(ROOT).decode("utf-8")
        for index, (filename, checksum) in enumerate(module.SOURCES, start=17):
            tag = f"$timing_jeju_migration_0{index}$"
            stored = re.search(re.escape(tag) + r"(.*?)" + re.escape(tag), sql, re.DOTALL)
            self.assertIsNotNone(stored)
            fetched = (stored.group(1) + ";\n").encode("utf-8")
            self.assertEqual((ATOMIC_MIGRATIONS / filename).read_bytes(), fetched)
            self.assertEqual(checksum, hashlib.sha256(fetched).hexdigest())

    def test_repository_layout_never_exposes_atomic_sources_to_raw_cli_sequence(self):
        """기본 CLI 디렉터리는 016까지만 가지며 017 이후 원문은 sibling에 격리한다."""
        cli_names = {path.name for path in (ROOT / "supabase/migrations").glob("*.sql")}
        atomic_names = {path.name for path in ATOMIC_MIGRATIONS.glob("*.sql")}
        expected_atomic = {
            "20260918000017_user_location_write_guard_purge.sql",
            "20260918000018_revision_request_hash_audit.sql",
            "20260918000019_planned_route_request_hash_policy.sql",
            "20260918000020_location_provenance_fail_closed.sql",
        }
        self.assertTrue(expected_atomic.isdisjoint(cli_names))
        self.assertTrue(expected_atomic.issubset(atomic_names))
        manifest = (ROOT / "supabase/migrations/manifest.json").read_text()
        for name in expected_atomic:
            self.assertIn(f'"path":"supabase/atomic-migrations/{name}"', manifest)

    def test_official_release_bootstraps_root_layout_then_runs_atomic_release(self):
        """공식 runner는 안전한 root bootstrap 뒤 017 이후를 한 psql transaction으로 실행한다."""
        release = (ROOT / "scripts/supabase-release.sh").read_text()
        self.assertIn("EXPECTED_CLI_VERSION=2.116.0", release)
        self.assertNotIn('if [ "$version" -lt 20260918000017 ]', release)
        self.assertNotIn('cp "$migration"', release)
        self.assertIn('--workdir "$ROOT" migration up', release)
        self.assertIn('location_cutover_supabase.sql', release)
        self.assertLess(release.index('migration up'), release.index('location_cutover_supabase.sql'))
        self.assertLess(release.index('location_cutover_supabase.sql'), release.index('migration list'))
        self.assertLess(release.index('migration list'), release.index('migration fetch'))
        self.assertIn('verify_supabase_fetched_migrations.py', release)

    def test_release_refuses_before_cli_without_database_target(self):
        """대상 누락은 CLI/DB mutation 전에 fail closed한다."""
        env = {"PATH": "/usr/bin:/bin", "SUPABASE_BIN": "must-not-run", "PSQL_BIN": "must-not-run"}
        result = subprocess.run(
            ["sh", "scripts/supabase-release.sh"], cwd=ROOT, env=env,
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(64, result.returncode)
        self.assertIn("TIMING_JEJU_DATABASE_URL", result.stderr)

    def test_fetched_inventory_verifier_accepts_exact_files_and_rejects_tamper(self):
        """list/fetch 후 원문 byte와 manifest SHA가 모두 같아야 release가 성공한다."""
        spec = importlib.util.spec_from_file_location(
            "verify_supabase_fetched_migrations",
            ROOT / "scripts/verify_supabase_fetched_migrations.py",
        )
        verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verifier)
        with tempfile.TemporaryDirectory() as directory:
            fetched = Path(directory)
            manifest = json.loads(
                (ROOT / "supabase/migrations/manifest.json").read_text()
            )
            for entry in manifest["immutablePrefix"] + manifest["canonicalSuffix"]:
                source = ROOT / entry["path"]
                shutil.copy2(source, fetched / source.name)
            verifier.verify(ROOT, fetched)
            target = fetched / self.load().SOURCES[0][0]
            target.write_bytes(target.read_bytes() + b"\n")
            with self.assertRaisesRegex(ValueError, "fetched migration differs"):
                verifier.verify(ROOT, fetched)
