from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "supabase/atomic-migrations/20260918000017_user_location_write_guard_purge.sql"


class LocationPurgeLockScopeTest(unittest.TestCase):
    def lock_scope(self) -> set[str]:
        """첫 감사 전에 실제 SQL이 잠그는 사용자 테이블 목록을 읽는다."""
        source = MIGRATION.read_text(encoding="utf-8")
        match = re.search(r"begin;\s*lock table\s+(.*?)\s+in access exclusive mode;", source, re.S)
        self.assertIsNotNone(match)
        return set(re.findall(r"public\.([a-z_]+)", match.group(1)))

    def test_audited_user_lineage_is_locked_before_any_projection_or_purge(self):
        """감사 후 hash와 출력이 바뀌지 않도록 관련 사용자 계보를 먼저 잠근다."""
        expected = {
            "trip_plans", "trip_schedule_versions", "compute_runs", "itinerary_generation_runs",
            "schedule_revision_runs", "compute_run_inputs", "trip_execution_events",
            "live_state_snapshots", "mcp_compute_call_logs", "itinerary_generation_candidates",
            "recovery_options", "recovery_option_changes", "trip_items", "trip_legs",
            "mobility_route_snapshots", "trip_preferences", "ai_conversations", "ai_messages",
            "risk_events", "trip_weather_impacts", "recommendation_candidates", "trip_item_progress",
            "api_idempotency_records",
        }
        self.assertEqual(self.lock_scope(), expected)

    def test_both_smoke_platforms_verify_post_seed_marker_and_zero_residue(self):
        """Bash와 PowerShell 모두 seed 이후 현재 DB의 전환 marker와 잔여량을 검사한다."""
        for name in ("docker-smoke-test.sh", "docker-smoke-test.ps1"):
            source = (ROOT / "scripts" / name).read_text(encoding="utf-8")
            with self.subTest(script=name):
                self.assertIn("user_location_guard_purge_revision()", source)
                self.assertIn("user_location_residue_counts()", source)
                self.assertIn("residue_count <> 0", source)
                self.assertIn("location cutover verification failed", source)

    def test_public_provider_tables_are_not_part_of_user_purge_lock_scope(self):
        """공개 관광지와 교통·날씨 원천은 사용자 위치 정리의 잠금 대상으로 혼동하지 않는다."""
        self.assertTrue(self.lock_scope().isdisjoint({
            "tour_places", "bus_stops", "bus_routes", "weather_grid_points",
            "weather_forecasts", "weather_observations", "user_profiles",
        }))
