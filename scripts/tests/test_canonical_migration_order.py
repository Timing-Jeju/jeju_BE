from __future__ import annotations

import hashlib
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "supabase/migrations/manifest.json"
BASELINE_SHA256 = "5e9a8ace21d938795ed9be7974a9e66311ae8f09f6ba6fdb1da1c496da2eab5a"
IMMUTABLE_LAST_SHA256 = "03f26da0d3fbd203e5ec245d55089edda062895ea19517e5d53fa356fefb195f"
IMMUTABLE_PREFIX_LOCK_SHA256 = (
    "8da6e207fab404610fb5ec3994663f0d5f10b8b9b2489efa9b79af3ac9e7bc57"
)

CANONICAL_SUFFIX = (
    ("20260918000000_trip_preferences_replace_contract.sql", "038", 46),
    ("20260918000001_trip_preferences_owner_read_helper.sql", "039", 46),
    ("20260918000002_trip_accommodation_contract.sql", "040", 68),
    ("20260918000003_trip_transport_event_contract.sql", "041", 47),
    ("20260918000004_trip_place_preference_contract.sql", "042", 48),
    ("20260918000005_trip_calendar_child_invariant_correction.sql", "043", 48),
    ("20260918000006_profile_image_storage.sql", "044", 78),
    ("20260918000007_schedule_item_required_references.sql", "045", 50),
    ("20260918000008_schedule_item_required_references_correction.sql", "046", 51),
    ("20260918000009_jeju_timetable_route_scope.sql", "047", 38),
    ("20260918000010_compute_run_input_location_cleanup.sql", "048", 109),
    ("20260918000011_private_trip_ownership_helper.sql", "049", 210),
    ("20260918000012_schedule_title_only_sealing_correction.sql", "050", 215),
    ("20260918000013_schedule_item_closed_facts.sql", "051", 225),
    ("20260918000014_planned_anchor_resolver.sql", "052", 225),
    ("20260918000015_planned_route_snapshot_provenance.sql", "053", 225),
    ("20260918000016_planned_route_reference_integrity.sql", "054", 225),
    ("20260918000017_user_location_write_guard_purge.sql", "055", 223),
    ("20260918000018_revision_request_hash_audit.sql", "056", 223),
    ("20260918000020_location_provenance_fail_closed.sql", "058", 223),
)

OLD_SUFFIX_PATHS = (
    "20260907000001_schedule_item_required_references.sql",
    "20260907000002_trip_accommodation_contract.sql",
    "20260907000003_trip_preferences_replace_contract.sql",
    "20260907000004_trip_preferences_owner_read_helper.sql",
    "20260907000005_trip_transport_event_contract.sql",
    "20260908000000_trip_place_preference_contract.sql",
    "20260909000000_trip_calendar_child_invariant_correction.sql",
    "20260913000000_profile_image_storage.sql",
    "20260915000000_jeju_timetable_route_scope.sql",
    "20260916000000_compute_run_input_location_cleanup.sql",
    "20260917000000_private_trip_ownership_helper.sql",
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class CanonicalMigrationOrderTest(unittest.TestCase):
    def test_architecture_documents_complete_canonical_suffix_and_title_only_correction(self) -> None:
        architecture = (ROOT / "docs/ARCHITECTURE.md").read_text(encoding="utf-8")

        self.assertIn("20260918000012", architecture)
        self.assertIn("Docker init `038`부터 `058`", architecture)
        self.assertIn("title-only", architecture)

    def test_suffix_paths_are_unique_monotonic_and_no_obsolete_path_survives(self) -> None:
        migration_dir = ROOT / "supabase/migrations"
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        actual = tuple(Path(entry["path"]).name for entry in manifest["canonicalSuffix"])
        expected = tuple(path for path, _, _ in CANONICAL_SUFFIX)
        self.assertEqual(expected, actual)
        self.assertEqual(len(expected), len({path[:14] for path in expected}))
        self.assertFalse((migration_dir / expected[-3]).exists())
        self.assertFalse((migration_dir / expected[-2]).exists())
        self.assertFalse((migration_dir / expected[-1]).exists())
        for obsolete in OLD_SUFFIX_PATHS:
            self.assertFalse((migration_dir / obsolete).exists(), obsolete)

    def test_manifest_freezes_origin_develop_prefix_and_suffix_ownership(self) -> None:
        self.assertTrue(MANIFEST.is_file(), "canonical migration manifest가 없습니다")
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        base = manifest["canonicalBase"]
        self.assertEqual("6cfa98fd3e65ba270eceea7150c843b33dbe2a56", base["commit"])
        self.assertEqual("d65a7466564bca5a43b56accfef2c8a031ef91aa", base["tree"])
        self.assertEqual(
            "20260907000000_schedule_item_create_contract.sql",
            base["immutableThrough"],
        )
        prefix = manifest["immutablePrefix"]
        self.assertEqual(35, len(prefix))
        self.assertEqual(IMMUTABLE_LAST_SHA256, prefix[-1]["sha256"])
        serialized_lock = "".join(
            f"{entry['path']}\0{entry['sha256']}\n" for entry in prefix
        ).encode()
        self.assertEqual(
            IMMUTABLE_PREFIX_LOCK_SHA256,
            hashlib.sha256(serialized_lock).hexdigest(),
        )
        for entry in prefix:
            path = ROOT / entry["path"]
            self.assertTrue(path.is_file(), entry["path"])
            self.assertEqual(entry["sha256"], digest(path), entry["path"])

        suffix = manifest["canonicalSuffix"]
        self.assertEqual([path for path, _, _ in CANONICAL_SUFFIX], [e["path"].split("/")[-1] for e in suffix])
        for entry, (path, slot, issue) in zip(suffix, CANONICAL_SUFFIX, strict=True):
            self.assertEqual(slot, entry["initSlot"])
            self.assertEqual(issue, entry["owner"]["issue"])
            self.assertIn("pullRequest", entry["owner"])
            self.assertTrue(entry["dependencies"])
            self.assertEqual(entry["sha256"], digest(ROOT / entry["path"]))
        for entry in suffix[-3:]:
            self.assertEqual("supabase/atomic-migrations", str(Path(entry["path"]).parent))
        ordered_names = [entry["path"].split("/")[-1] for entry in (*prefix, *suffix)]
        for entry in suffix:
            current_index = ordered_names.index(entry["path"].split("/")[-1])
            for dependency in entry["dependencies"]:
                self.assertLess(ordered_names.index(dependency), current_index)

        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            positions = []
            for entry in (*prefix, *suffix):
                if entry["initSlot"] == "056":
                    continue
                entry = dict(entry)
                if entry["initSlot"] == "055":
                    entry["path"] = "db/local-postgres/20260918000017_location_cutover_group.sql"
                mount = re.search(
                    rf"\./{re.escape(entry['path'])}:"
                    rf"/docker-entrypoint-initdb\.d/{entry['initSlot']}_[^:]+\.sql:ro",
                    compose,
                )
                self.assertIsNotNone(mount, f"{compose_name}: {entry['path']}")
                positions.append(mount.start())
            self.assertEqual(sorted(positions), positions, compose_name)
        manifest_text = MANIFEST.read_text(encoding="utf-8")
        self.assertNotIn("20260907000001_schedule_item_required_references.sql", manifest_text)
        self.assertNotIn("cf086714", manifest_text.lower(), "PR #209 overwrite blob must not be canonical")

    def test_issue_50_baseline_blob_and_issue_51_additive_correction_are_separate(self) -> None:
        baseline_path = ROOT / "supabase/migrations" / CANONICAL_SUFFIX[7][0]
        correction_path = ROOT / "supabase/migrations" / CANONICAL_SUFFIX[8][0]
        self.assertEqual(BASELINE_SHA256, digest(baseline_path))
        baseline = baseline_path.read_text(encoding="utf-8").lower()
        correction = re.sub(r"\s+", " ", correction_path.read_text(encoding="utf-8").lower())

        self.assertIn("unique (id, trip_plan_id, event_type)", baseline)
        self.assertNotIn("place_visit", baseline)
        self.assertIn("legacy schedule item required reference audit failed", correction)
        self.assertIn("drop trigger trg_trip_items_required_references", correction)
        self.assertIn("drop constraint chk_trip_items_required_references", correction)
        self.assertIn("create or replace function public.validate_trip_item_required_references", correction)
        self.assertIn("update of item_type, trip_plan_id, place_id, accommodation_id, transport_event_id, title", correction)
        self.assertIn("create or replace function public.assert_schedule_item_required_references", correction)
        self.assertNotIn("uq_trip_transport_events_id_plan_event_type", correction)
        self.assertNotIn("fk_trip_items_transport_event_type_plan", correction)
        self.assertNotIn("rename to assert_schedule_version_core_sealable", correction)
        for signature in (
            "public.validate_trip_item_required_references()",
            "public.assert_schedule_item_required_references(uuid, uuid)",
        ):
            self.assertIn(f"revoke all on function {signature} from public", correction)
            self.assertIn(f"revoke execute on function {signature} from anon", correction)
            self.assertIn(f"revoke execute on function {signature} from authenticated", correction)

    def test_issue_215_additively_aligns_core_sealing_with_title_only_items(self) -> None:
        correction_path = ROOT / "supabase/migrations/20260918000012_schedule_title_only_sealing_correction.sql"
        source = re.sub(r"\s+", " ", correction_path.read_text(encoding="utf-8").lower())

        self.assertIn(
            "create or replace function public.assert_schedule_version_core_sealable", source
        )
        self.assertIn("i.item_type not in ('meal', 'free_time', 'custom')", source)
        self.assertIn("sealed schedule items require a place or explicit location facts", source)
        self.assertIn(
            "revoke execute on function public.assert_schedule_version_core_sealable(uuid, uuid) from authenticated",
            source,
        )

    def test_planned_resolver_smoke_loops_include_public_tombstone_dependency(self) -> None:
        """계획 anchor resolver를 실행하는 모든 smoke 경로가 공개 장소 삭제 컬럼을 먼저 준비한다."""
        source = (ROOT / "scripts/docker-smoke-test.sh").read_text(encoding="utf-8")
        resolver = "/docker-entrypoint-initdb.d/052_planned_anchor_resolver.sql"
        dependency = "/docker-entrypoint-initdb.d/023_public_place_tombstone.sql"
        blocks = re.findall(r"for \w+ in(.*?)\ndo", source, re.DOTALL)
        selected = [block for block in blocks if resolver in block]
        self.assertEqual(2, len(selected))
        for block in selected:
            self.assertIn(dependency, block)
            self.assertLess(block.index(dependency), block.index(resolver))

    def test_compose_and_both_smoke_scripts_follow_the_manifest(self) -> None:
        expected_mounts = []
        for path, slot, _ in CANONICAL_SUFFIX:
            if slot == "056":
                continue
            if slot == "055":
                expected_mounts.append(
                    ("./db/local-postgres/20260918000017_location_cutover_group.sql",
                     "/docker-entrypoint-initdb.d/055_location_cutover_group.sql")
                )
                continue
            source_dir = "supabase/atomic-migrations" if slot == "058" else "supabase/migrations"
            expected_mounts.append(
                (f"./{source_dir}/{path}", f"/docker-entrypoint-initdb.d/{slot}_{path[15:]}")
            )
        seed = "/docker-entrypoint-initdb.d/099_seed_fixtures.sql"
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            source = (ROOT / compose_name).read_text(encoding="utf-8")
            positions = []
            for migration, target in expected_mounts:
                mount = f"{migration}:{target}:ro"
                self.assertEqual(1, source.count(mount), f"{compose_name}: {mount}")
                positions.append(source.index(mount))
            self.assertEqual(sorted(positions), positions, compose_name)
            self.assertLess(positions[-1], source.index(seed), compose_name)

        shell = (ROOT / "scripts/docker-smoke-test.sh").read_text(encoding="utf-8")
        for _, target in expected_mounts:
            self.assertIn(target, shell)
        powershell = (ROOT / "scripts/docker-smoke-test.ps1").read_text(encoding="utf-8")
        self.assertIn("supabase/migrations/manifest.json", powershell)
        self.assertIn("immutablePrefix", powershell)
        self.assertIn("canonicalSuffix", powershell)
        for source in (shell, powershell):
            self.assertIn("canonical_origin_develop_upgrade", source.lower())
            self.assertIn("canonical_schedule_50_51_upgrade", source.lower())
            self.assertIn("canonical_migration_fingerprint.sql", source)
            self.assertIn("database_concurrency_contract.sql", source)

    def test_actual_postgresql_contract_source_covers_all_upgrade_paths(self) -> None:
        integration = ROOT / (
            "services/spring-api/src/test/java/com/timingjeju/api/support/postgresql/"
            "CanonicalMigrationOrderIntegrationTest.java"
        )
        fingerprint = ROOT / "db/queries/canonical_migration_fingerprint.sql"
        self.assertTrue(integration.is_file())
        self.assertTrue(fingerprint.is_file())
        source = integration.read_text(encoding="utf-8")
        for marker in (
            "freshInstall",
            "originDevelopUpgrade",
            "schedule50Then51Upgrade",
            "preflightRollback",
            "schemaAndAclFingerprint",
            "6cfa98fd3e65ba270eceea7150c843b33dbe2a56",
        ):
            self.assertIn(marker, source)
        fingerprint_sql = fingerprint.read_text(encoding="utf-8").lower()
        for catalog in ("pg_constraint", "pg_trigger", "pg_policies", "information_schema.role_table_grants", "aclexplode"):
            self.assertIn(catalog, fingerprint_sql)

    def test_fingerprint_excludes_extension_routines_and_projects_security_acl(self) -> None:
        fingerprint_sql = (ROOT / "db/queries/canonical_migration_fingerprint.sql").read_text(
            encoding="utf-8"
        ).lower()
        for marker in (
            "procedure_record.prokind in ('f', 'p', 'w')",
            "pg_catalog.pg_depend",
            "dependency_record.deptype = 'e'",
            "relation_acl",
            "column_acl",
            "schema_acl",
            "schema_owner",
            "policy_record.permissive",
            "policy_record.roles",
        ):
            self.assertIn(marker, fingerprint_sql)
        self.assertIn("dependency_record.classid = 'pg_proc'::regclass", fingerprint_sql)
        self.assertIn("not exists", fingerprint_sql)

    def test_fingerprint_serializes_catalog_scalar_types_without_ambiguous_concat(self) -> None:
        fingerprint_sql = (ROOT / "db/queries/canonical_migration_fingerprint.sql").read_text(
            encoding="utf-8"
        ).lower()
        for marker in (
            "relation.relkind::text",
            "relation.relrowsecurity::text",
            "relation.relforcerowsecurity::text",
            "acl_record.is_grantable::text",
        ):
            self.assertIn(marker, fingerprint_sql)

    def test_storage_policy_actual_postgresql_contract_covers_pg16_and_pg17(self) -> None:
        source = (ROOT / (
            "services/spring-api/src/test/java/com/timingjeju/api/support/postgresql/"
            "ProfileImageStoragePolicyMigrationIntegrationTest.java"
        )).read_text(encoding="utf-8").lower()
        for marker in (
            "postgis/postgis:16-3.4",
            "postgis/postgis:17-3.5",
            "returning id",
            'versiondatasource,\n                        "anon"',
            "update storage.objects",
            "delete from storage.objects",
        ):
            self.assertIn(marker, source)

    def test_powershell_bootstraps_auth_before_both_manifest_replays(self) -> None:
        powershell = (ROOT / "scripts/docker-smoke-test.ps1").read_text(encoding="utf-8")
        auth = 'Invoke-SqlFile $database "/docker-entrypoint-initdb.d/001_auth_compat.sql"'
        self.assertIn(auth, powershell)
        helper = powershell.index("function Invoke-CanonicalManifest")
        auth_position = powershell.index(auth, helper)
        prefix_position = powershell.index("foreach ($entry in $manifest.immutablePrefix)", helper)
        suffix_position = powershell.index("foreach ($entry in $manifest.canonicalSuffix)", helper)
        self.assertLess(auth_position, prefix_position)
        self.assertLess(prefix_position, suffix_position)
        self.assertIn("Invoke-CanonicalManifest $originDevelopDatabase", powershell)
        self.assertIn("Invoke-CanonicalManifest $concurrencyDatabase", powershell)

    def test_actual_postgresql_source_covers_postgis_versions_and_acl_mutations(self) -> None:
        source = (ROOT / (
            "services/spring-api/src/test/java/com/timingjeju/api/support/postgresql/"
            "CanonicalMigrationOrderIntegrationTest.java"
        )).read_text(encoding="utf-8")
        for marker in (
            "postgis/postgis:16-3.4",
            "postgis/postgis:17-3.5",
            "isnotblank",
            "grant select (token_ciphertext) on public.push_devices to authenticated",
            "grant select on public.push_devices to public",
            "grant usage on schema auth to authenticated",
            "grant create on schema timing_jeju_private to authenticated",
            "alter policy push_devices_owner_select",
            "as restrictive",
        ):
            self.assertIn(marker, source.lower())

    def test_ci_does_not_automatically_apply_supabase_migrations(self) -> None:
        workflows = ROOT / ".github/workflows"
        sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(workflows.glob("*.y*ml"))
        ).lower()
        self.assertNotRegex(sources, r"\bsupabase\s+db\s+(push|reset)\b")


if __name__ == "__main__":
    unittest.main()
