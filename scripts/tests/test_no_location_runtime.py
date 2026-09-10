"""Issue #224: 위치 수집·정리 런타임이 다시 활성화되지 않는 구조를 검증한다."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "services/spring-api/src/main"
JAVA = MAIN / "java/com/timingjeju/api"


class NoLocationRuntimeTest(unittest.TestCase):
    def test_location_models_resolver_and_cleanup_runtime_are_absent(self):
        """현재 위치 모델·조회기·TTL 정리 구현이 운영 classpath 소스에 없다."""
        forbidden = [
            JAVA / "application/commandinput" / (name + ".java")
            for name in ("CoarseLocation", "CommandLocation", "CommandLocationSnapshot", "McpCommandLocationResolver")
        ]
        for layer in ("application", "global"):
            forbidden.extend((JAVA / layer / "commandinput/cleanup").glob("*.java"))
        self.assertEqual([], [str(path.relative_to(ROOT)) for path in forbidden if path.exists()])

    def test_application_configuration_cannot_enable_location_cleanup(self):
        """환경변수로 위치 정리 bean과 스케줄러를 켜는 설정이 없다."""
        config = (MAIN / "resources/application.yml").read_text()
        self.assertNotIn("command-location-cleanup", config)
        self.assertNotIn("COMMAND_LOCATION_CLEANUP", config)

    def test_worker_claim_and_exhaustion_require_supported_schema(self):
        """claim과 재시도 종료는 읽을 수 있는 v2 lineage만 대상으로 한다."""
        source = (JAVA / "global/asyncrun/JdbcRunLeaseRepository.java").read_text()
        self.assertEqual(2, source.count("and input.schema_version = 2"))
        for column in ("location_supplied", "coarse_location", "location_precision_meters",
                       "location_policy_version", "location_observed_at", "location_expires_at",
                       "location_redacted_at"):
            self.assertNotIn("input." + column, source)
