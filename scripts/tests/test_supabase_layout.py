from __future__ import annotations

import re
import tomllib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SUPABASE = ROOT / "supabase"
INITIAL_MIGRATION = SUPABASE / "migrations" / "20260728000000_initial_public_schema.sql"


EXPECTED_TABLES = {
    "ai_conversations",
    "ai_messages",
    "app_sessions",
    "bus_arrival_snapshots",
    "bus_routes",
    "bus_stops",
    "compute_runs",
    "data_import_runs",
    "itinerary_generation_candidates",
    "itinerary_generation_runs",
    "legal_documents",
    "live_state_snapshots",
    "mcp_compute_call_logs",
    "mobility_route_snapshots",
    "place_aliases",
    "place_details",
    "place_images",
    "place_operating_hours",
    "place_stop_links",
    "recommendation_candidates",
    "recovery_option_changes",
    "recovery_options",
    "risk_events",
    "route_stops",
    "saved_places",
    "social_accounts",
    "timetable_entries",
    "tour_places",
    "trip_accommodations",
    "trip_days",
    "trip_execution_events",
    "trip_item_progress",
    "trip_items",
    "trip_legs",
    "trip_place_preferences",
    "trip_plans",
    "trip_preferences",
    "trip_schedule_versions",
    "trip_transport_events",
    "trip_transport_modes",
    "trip_weather_impacts",
    "user_consents",
    "user_profiles",
    "weather_forecasts",
    "weather_grid_points",
    "weather_observations",
}


class SupabaseLayoutTest(unittest.TestCase):
    def test_cli_project_uses_versioned_migrations_and_empty_safe_seed(self):
        config = (SUPABASE / "config.toml").read_text(encoding="utf-8")
        seed = (SUPABASE / "seed.sql").read_text(encoding="utf-8")

        self.assertIn('project_id = "timing-jeju"', config)
        self.assertIn("[db.migrations]", config)
        self.assertIn("enabled = true", config)
        self.assertIn('sql_paths = ["./seed.sql"]', config)
        self.assertNotRegex(seed, r"(?i)\binsert\s+into\b")

    def test_initial_migration_preserves_public_schema_inventory(self):
        migration = INITIAL_MIGRATION.read_text(encoding="utf-8")
        tables = set(re.findall(r"(?im)^create table ([a-z_]+)", migration))

        self.assertEqual(EXPECTED_TABLES, tables)
        for extension in ("pgcrypto", "postgis", "btree_gist"):
            with self.subTest(extension=extension):
                self.assertRegex(
                    migration,
                    rf"(?im)^create extension if not exists {extension};$",
                )
        self.assertEqual(142, len(re.findall(r"(?im)^create (?:unique )?index ", migration)))
        self.assertEqual(11, len(re.findall(r"(?im)^create (?:or replace )?function ", migration)))
        self.assertEqual(12, len(re.findall(r"(?im)^create trigger ", migration)))
        self.assertEqual(26, len(re.findall(r"(?im)^create policy ", migration)))

    def test_general_postgres_compose_uses_local_auth_then_canonical_migration(self):
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertIn("./db/local-postgres/auth_compat.sql", compose)
                self.assertIn(
                    "./supabase/migrations/20260728000000_initial_public_schema.sql",
                    compose,
                )
                self.assertIn("./db/local-postgres/seed_fixtures.sql", compose)
                self.assertNotIn("./db/init", compose)

    def test_docker_smoke_test_executes_postgis_schema_contract(self):
        compose = (ROOT / "compose.test.yml").read_text(encoding="utf-8")
        smoke_test = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("./db/queries:/queries:ro", compose)
        self.assertIn("psql", smoke_test)
        self.assertIn("/queries/smoke_check.sql", smoke_test)

    def test_recommended_stay_policy_migration_is_after_reserved_slots_everywhere(self):
        migration_name = "20260823000000_recommended_stay_policy.sql"
        migration = SUPABASE / "migrations" / migration_name
        self.assertTrue(migration.is_file())
        self.assertGreater(migration_name[:14], "20260822000000")
        sql = migration.read_text(encoding="utf-8")
        self.assertIn("create table public.place_stay_policy_versions", sql.lower())
        self.assertIn("create table public.place_stay_policies", sql.lower())
        self.assertNotRegex(sql.lower(), r"(?:insert into|update|delete from)\s+public\.tour_places")

        mount = (
            f"./supabase/migrations/{migration_name}:"
            "/docker-entrypoint-initdb.d/021_recommended_stay_policy.sql:ro"
        )
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertIn(mount, compose)
                self.assertEqual(1, compose.count("021_recommended_stay_policy.sql"))
                self.assertIn("/docker-entrypoint-initdb.d/099_seed_fixtures.sql", compose)

                conditional_order = [
                    (
                        "20260819000000_tago_stop_import.sql",
                        "/docker-entrypoint-initdb.d/016_tago_stop_import.sql",
                    ),
                    (
                        "20260820000000_tago_route_stops_import.sql",
                        "/docker-entrypoint-initdb.d/017_tago_route_stops_import.sql",
                    ),
                    (
                        "20260820000001_kma_village_forecast_version.sql",
                        "/docker-entrypoint-initdb.d/018_kma_village_forecast_version.sql",
                    ),
                    (
                        "20260822000000_place_stop_postgis_links.sql",
                        "/docker-entrypoint-initdb.d/020_place_stop_postgis_links.sql",
                    ),
                    (
                        migration_name,
                        "/docker-entrypoint-initdb.d/021_recommended_stay_policy.sql",
                    ),
                    (
                        "20260824000000_tourapi_discovery_import_checkpoints.sql",
                        "/docker-entrypoint-initdb.d/022_tourapi_discovery_import_checkpoints.sql",
                    ),
                    (
                        "20260825000000_public_place_tombstone.sql",
                        "/docker-entrypoint-initdb.d/023_public_place_tombstone.sql",
                    ),
                    (
                        "20260826000000_tago_arrival_cache.sql",
                        "/docker-entrypoint-initdb.d/024_tago_arrival_cache.sql",
                    ),
                    (
                        "20260827000000_tago_arrival_flight_state.sql",
                        "/docker-entrypoint-initdb.d/025_tago_arrival_flight_state.sql",
                    ),
                ]
                positions = []
                for source_name, target_name in conditional_order:
                    if (SUPABASE / "migrations" / source_name).is_file():
                        self.assertIn(target_name, compose)
                        positions.append(compose.index(target_name))
                self.assertEqual(sorted(positions), positions)

        smoke_test = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )
        self.assertGreaterEqual(
            smoke_test.count(
                "/docker-entrypoint-initdb.d/021_recommended_stay_policy.sql"
            ),
            2,
        )

    def test_tago_arrival_flight_migration_mount_is_canonical_and_seed_remains_last(self):
        migration_name = "20260827000000_tago_arrival_flight_state.sql"
        migration = SUPABASE / "migrations" / migration_name
        self.assertTrue(migration.is_file())
        mount = (
            f"./supabase/migrations/{migration_name}:"
            "/docker-entrypoint-initdb.d/025_tago_arrival_flight_state.sql:ro"
        )

        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertEqual(1, compose.count(mount))
                self.assertLess(
                    compose.index("/docker-entrypoint-initdb.d/024_tago_arrival_cache.sql"),
                    compose.index("/docker-entrypoint-initdb.d/025_tago_arrival_flight_state.sql"),
                )
                self.assertLess(
                    compose.index("/docker-entrypoint-initdb.d/025_tago_arrival_flight_state.sql"),
                    compose.index("/docker-entrypoint-initdb.d/099_seed_fixtures.sql"),
                )

    def test_completed_provider_health_index_is_additive_covering_and_canonically_mounted(self):
        migration_name = "20260828000000_completed_provider_data_health_index.sql"
        migration = SUPABASE / "migrations" / migration_name
        self.assertTrue(migration.is_file())
        sql = migration.read_text(encoding="utf-8").lower()

        self.assertIn("create index idx_data_import_runs_completed_health_latest", sql)
        self.assertRegex(
            sql,
            r"source_provider\s*,\s*source_service\s*,\s*source_operation\s*,"
            r"\s*started_at\s+desc\s*,\s*id\s+desc",
        )
        self.assertRegex(sql, r"include\s*\(\s*status\s*,\s*finished_at\s*\)")
        for predicate in (
            "idempotency_key is not null",
            "idempotency_enforced",
            "running_scope_enforced",
            "status in ('succeeded', 'failed', 'partial', 'cancelled')",
            "finished_at is not null",
        ):
            self.assertIn(predicate, sql)
        self.assertNotRegex(sql, r"\b(?:alter\s+table|insert|update|delete)\b")

        source = f"./supabase/migrations/{migration_name}"
        target = "/docker-entrypoint-initdb.d/026_completed_provider_data_health_index.sql"
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertEqual(1, compose.count(f"{source}:{target}:ro"))
                self.assertLess(
                    compose.index("/docker-entrypoint-initdb.d/025_tago_arrival_flight_state.sql"),
                    compose.index(target),
                )
                self.assertLess(
                    compose.index(target),
                    compose.index("/docker-entrypoint-initdb.d/099_seed_fixtures.sql"),
                )

        smoke_test = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )
        self.assertGreaterEqual(smoke_test.count(target), 2)
        database_docs = (
            ROOT / "docs" / "designs" / "timing-jeju-db-schema-v0.md"
        ).read_text(encoding="utf-8")
        self.assertIn(migration_name, database_docs)
        self.assertIn("idx_data_import_runs_completed_health_latest", database_docs)

    def test_snapshot_retention_index_is_partial_ordered_and_canonically_mounted(self):
        migration_name = "20260829000000_completed_provider_snapshot_retention_index.sql"
        migration = SUPABASE / "migrations" / migration_name
        self.assertTrue(migration.is_file())
        sql = migration.read_text(encoding="utf-8").lower()

        self.assertIn("create index idx_external_api_snapshots_retention_due", sql)
        self.assertIn("drop index public.idx_external_api_snapshots_purge", sql)
        self.assertLess(
            sql.index("create index idx_external_api_snapshots_retention_due"),
            sql.index("drop index public.idx_external_api_snapshots_purge"),
        )
        self.assertRegex(
            sql,
            r"on\s+public\.external_api_snapshots\s*\(\s*purge_after\s*,\s*id\s*\)",
        )
        for predicate in (
            "purge_after is not null",
            "purged_at is null",
            "raw_payload is not null",
        ):
            self.assertIn(predicate, sql)
        self.assertNotRegex(sql, r"\b(?:alter\s+table|insert|update|delete)\b")

        source = f"./supabase/migrations/{migration_name}"
        target = "/docker-entrypoint-initdb.d/027_completed_provider_snapshot_retention_index.sql"
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertEqual(1, compose.count(f"{source}:{target}:ro"))
                self.assertLess(
                    compose.index(
                        "/docker-entrypoint-initdb.d/026_completed_provider_data_health_index.sql"
                    ),
                    compose.index(target),
                )
                self.assertLess(
                    compose.index(target),
                    compose.index("/docker-entrypoint-initdb.d/099_seed_fixtures.sql"),
                )

        smoke_test = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )
        self.assertGreaterEqual(smoke_test.count(target), 2)
        database_docs = (
            ROOT / "docs" / "designs" / "timing-jeju-db-schema-v0.md"
        ).read_text(encoding="utf-8")
        self.assertIn(migration_name, database_docs)
        self.assertIn("idx_external_api_snapshots_retention_due", database_docs)
        self.assertNotIn("retention으로 snapshot을 삭제", database_docs)
        self.assertNotIn("snapshot 포인터만 null", database_docs.lower())
        self.assertIn("raw_payload=null", database_docs.lower())
        self.assertIn("purged_at=now", database_docs.lower())
        dbml = (
            ROOT / "docs" / "designs" / "timing-jeju-dbdiagram.dbml"
        ).read_text(encoding="utf-8")
        self.assertIn("#164 snapshot retention index `/027`", dbml)

        schema_contract = (ROOT / "db" / "queries" / "schema_contract.sql").read_text(
            encoding="utf-8"
        ).lower()
        legacy_contract = (
            ROOT / "db" / "queries" / "legacy_v1_upgrade_contract.sql"
        ).read_text(encoding="utf-8").lower()
        for contract in (schema_contract, legacy_contract):
            self.assertIn("idx_external_api_snapshots_retention_due", contract)
            self.assertIn("idx_external_api_snapshots_purge", contract)
            self.assertRegex(
                contract,
                r"to_regclass\([^\n]+idx_external_api_snapshots_retention_due[^\n]+\)\s+is\s+not\s+null",
            )
            self.assertRegex(
                contract,
                r"to_regclass\([^\n]+idx_external_api_snapshots_purge[^\n]+\)\s+is\s+null",
            )

    def test_recommended_stay_policy_dbml_matches_canonical_migration(self):
        dbml = (
            ROOT / "docs" / "designs" / "timing-jeju-dbdiagram.dbml"
        ).read_text(encoding="utf-8")

        expected_contracts = (
            "#37 place-link `/020`, #65 stay-policy `/021`, #39 arrival `/024`, #39 flight-state `/025`, #160 completed-health index `/026`, #164 snapshot retention index `/027`, seed `/099`",
            "Table place_stay_policy_versions {",
            "version text [pk]",
            "status text [not null, note: \"draft, active, retired\"]",
            "payload_hash text [not null, note: \"SHA-256 lowercase hex, 64 chars\"]",
            "effective_at timestamptz [not null]",
            "imported_at timestamptz [not null]",
            "(status) [unique, note: \"partial: status = 'active'\"]",
            "version syntax: ^[a-z0-9][a-z0-9._-]{0,63}$",
            "payload_hash syntax: ^[0-9a-f]{64}$",
            "Table place_stay_policies {",
            "version text [not null, ref: > place_stay_policy_versions.version]",
            "scope text [not null, note: \"category_default, place_override\"]",
            "place_id uuid [ref: > tour_places.id]",
            "minutes integer [not null, note: \"5..1440\"]",
            "source text [not null, default: 'app_curation']",
            "(version, category) [unique, note: \"partial: scope = 'category_default'\"]",
            "(version, place_id) [unique, note: \"partial: scope = 'place_override'\"]",
            "(place_id, version) [note: \"partial lookup: scope = 'place_override'\"]",
            "(category, version) [note: \"partial lookup: scope = 'category_default'\"]",
            "category/place_id XOR",
            "effective_at <= imported_at",
            "category syntax: ^[A-Za-z0-9:_-]{1,64}$",
            "both FKs ON DELETE RESTRICT",
        )
        for expected in expected_contracts:
            with self.subTest(contract=expected):
                self.assertIn(expected, dbml)

    def test_public_place_tombstone_migration_is_additive_and_mounted_last(self):
        migration_name = "20260825000000_public_place_tombstone.sql"
        migration = SUPABASE / "migrations" / migration_name
        self.assertTrue(migration.is_file())
        self.assertGreater(migration_name[:14], "20260824000000")
        sql = migration.read_text(encoding="utf-8").lower()
        self.assertRegex(
            sql,
            r"alter\s+table\s+public\.tour_places\s+add\s+column\s+tombstoned_at\s+timestamptz",
        )
        self.assertNotRegex(sql, r"\b(?:insert|update|delete)\b")
        self.assertNotIn("create index", sql)

        dbml = (
            ROOT / "docs" / "designs" / "timing-jeju-dbdiagram.dbml"
        ).read_text(encoding="utf-8")
        tour_places = re.search(r"Table tour_places \{(?P<body>.*?)\n\}", dbml, re.DOTALL)
        tour_place_sources = re.search(
            r"Table tour_place_sources \{(?P<body>.*?)\n\}", dbml, re.DOTALL
        )
        self.assertIsNotNone(tour_places)
        self.assertIsNotNone(tour_place_sources)
        self.assertEqual(1, tour_places.group("body").count("tombstoned_at timestamptz"))
        self.assertEqual(
            1, tour_place_sources.group("body").count("tombstoned_at timestamptz")
        )

        source = f"./supabase/migrations/{migration_name}"
        target = "/docker-entrypoint-initdb.d/023_public_place_tombstone.sql"
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            compose = (ROOT / compose_name).read_text(encoding="utf-8")
            with self.subTest(compose=compose_name):
                self.assertIn(f"{source}:{target}:ro", compose)
                self.assertLess(compose.index(target), compose.index("099_seed_fixtures.sql"))

    def test_flyway_is_not_added_as_a_second_migration_system(self):
        self.assertFalse((ROOT / "db" / "migration").exists())
        spring_files = (
            ROOT / "services" / "spring-api" / "build.gradle",
            ROOT / "services" / "spring-api" / "src/main/resources/application.yml",
        )
        for path in spring_files:
            with self.subTest(path=path):
                self.assertNotIn("flyway", path.read_text(encoding="utf-8").lower())

    def test_supabase_smoke_test_is_repeatable_and_always_cleans_up(self):
        smoke_test = (ROOT / "scripts" / "supabase-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn('SUPABASE_BIN=${SUPABASE_BIN:-supabase}', smoke_test)
        self.assertIn('DOCKER_BIN=${DOCKER_BIN:-docker}', smoke_test)
        self.assertIn('trap cleanup EXIT INT TERM', smoke_test)
        self.assertIn('"$SUPABASE_BIN" start', smoke_test)
        self.assertEqual(2, smoke_test.count('"$SUPABASE_BIN" db reset'))
        self.assertIn('"$SUPABASE_BIN" stop --no-backup', smoke_test)
        self.assertIn("redirect_to", smoke_test)
        self.assertIn("https://evil.invalid/social-callback", smoke_test)
        self.assertIn("미등록 redirect URL 차단 확인 성공", smoke_test)

    def test_docker_smoke_removes_its_dedicated_compose_resources_and_image(self):
        smoke_test = (ROOT / "scripts" / "docker-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("docker compose -p \"$PROJECT\"", smoke_test)
        self.assertIn("down -v --remove-orphans", smoke_test)
        self.assertIn('docker image rm "${PROJECT}-api:latest"', smoke_test)

    def test_supabase_redirect_smoke_reaches_email_link_allowlist_validation(self):
        smoke_test = (ROOT / "scripts" / "supabase-smoke-test.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("/auth/v1/admin/generate_link", smoke_test)
        self.assertIn("SERVICE_ROLE_KEY", smoke_test)
        self.assertIn("http://127.0.0.1:3000/auth/callback", smoke_test)
        self.assertIn("https://evil.invalid/social-callback", smoke_test)
        self.assertIn('response.get("redirect_to")', smoke_test)
        self.assertNotIn("provider=google", smoke_test)
        self.assertNotIn("unsupported_provider", smoke_test)
        self.assertNotIn("REDIRECT_LOCATION", smoke_test)

    def test_common_quality_gate_runs_deploy_sql_policy_independently(self):
        quality_gate = (ROOT / "scripts" / "quality-gate.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("python3 scripts/deploy_sql_policy.py", quality_gate)

    def test_database_docs_explain_conservative_dynamic_execute_policy(self):
        database_docs = (ROOT / "db" / "README.md").read_text(encoding="utf-8")

        self.assertIn("`EXECUTE`", database_docs)
        self.assertIn("보수적", database_docs)
        self.assertIn("문자열 연결", database_docs)
        self.assertIn("`format(...)`", database_docs)
        self.assertIn("의미 분석", database_docs)
        self.assertIn("코드 리뷰", database_docs)

    def test_supabase_redirect_urls_are_the_only_social_redirect_authority(self):
        config = tomllib.loads((SUPABASE / "config.toml").read_text(encoding="utf-8"))
        redirect_urls = config["auth"]["additional_redirect_urls"]

        self.assertEqual(
            [
                "http://127.0.0.1:3000",
                "http://127.0.0.1:3000/auth/callback",
            ],
            redirect_urls,
        )
        self.assertTrue(all("*" not in url and "?" not in url and "#" not in url for url in redirect_urls))
        for relative_path in (
            ".env.example",
            "compose.yml",
            "compose.test.yml",
            "services/spring-api/src/main/resources/application.yml",
        ):
            with self.subTest(path=relative_path):
                contents = (ROOT / relative_path).read_text(encoding="utf-8")
                self.assertNotIn("APP_SOCIAL_LOGIN_REDIRECT_URLS", contents)
                self.assertNotIn("app.social-login.redirect-urls", contents)

    def test_unconfigured_local_oauth_providers_are_not_advertised_as_enabled(self):
        env_example = (ROOT / ".env.example").read_text(encoding="utf-8")
        docs = (ROOT / "docs" / "SOCIAL_LOGIN.md").read_text(encoding="utf-8")

        self.assertNotIn("SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID", env_example)
        self.assertNotIn("SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_SECRET", env_example)
        self.assertNotIn("SUPABASE_AUTH_EXTERNAL_KAKAO_CLIENT_ID", env_example)
        self.assertNotIn("SUPABASE_AUTH_EXTERNAL_KAKAO_CLIENT_SECRET", env_example)
        self.assertIn("지원 목록이며 실제 Supabase 활성화 상태가 아닙니다", docs)
        self.assertIn("로컬 OAuth 공급자는 기본 비활성화", docs)

    def test_naver_docs_do_not_claim_unsupported_pkce_or_verified_email(self):
        docs = (ROOT / "docs" / "SOCIAL_LOGIN.md").read_text(encoding="utf-8")

        self.assertNotIn("email_verified=true", docs)
        self.assertNotIn("PKCE는 기본 활성 상태를 유지", docs)
        self.assertIn("Naver는 이메일 검증 여부를 제공하지 않습니다", docs)
        self.assertIn("자동 identity 연결", docs)
        self.assertIn("code_challenge", docs)
        self.assertIn("code_verifier", docs)
        self.assertIn("scope는 전송할 필요 없음", docs)
        self.assertIn("실제 호환성 검증 전에는 운영에서 활성화하지 않습니다", docs)


if __name__ == "__main__":
    unittest.main()
