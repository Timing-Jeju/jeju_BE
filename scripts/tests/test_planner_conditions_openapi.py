"""플래너 조건 저장의 공개 헤더 계약을 검증한다."""

import unittest
import json
from pathlib import Path

from scripts.validate_openapi_frontend_readiness import (
    REQUIRED_REQUEST_HEADERS,
    REQUIRED_RESPONSE_HEADERS,
    operations_for_mode,
    Validator,
)


class PlannerConditionsOpenApiTest(unittest.TestCase):
    def test_runtime_handoff_covers_every_active_operation(self):
        """FE 인계 manifest는 조건 저장과 생성 접수·조회·적용을 하나도 빠뜨리지 않는다."""
        root = Path(__file__).resolve().parents[2]
        manifest = json.loads((root / "scripts/openapi_frontend_runtime_manifest.json").read_text())
        self.assertEqual(
            {f"{method} {path}" for method, path in operations_for_mode(43)},
            set(manifest["operations"]),
        )

    def test_candidate_schedule_url_has_an_implemented_operation(self):
        """후보와 적용 응답이 가리키는 버전 URL을 신규 인계 목록에서 빠뜨리지 않는다."""
        previous = operations_for_mode(42)
        current = operations_for_mode(43)
        self.assertEqual(43, len(current))
        self.assertEqual(previous, {key: current[key] for key in previous})
        self.assertEqual("tripScheduleVersionRead", current[("GET", "/api/v1/trips/{tripId}/schedule-versions/{versionId}")])

    def test_generation_examples_enforce_phase_specific_presence(self):
        """대기 응답의 생략은 허용하되 실행 시작 누락과 대기 중 완료 필드는 거부한다."""
        fields = ("status", "startedAt", "completedAt", "result", "failure")
        schema = {
            "type": "object", "additionalProperties": False,
            "properties": {name: {"type": "string"} for name in fields},
            "required": ["status"],
        }
        document = {"components": {"schemas": {"GenerationRunStatus": schema}}}
        reference = {"$ref": "#/components/schemas/GenerationRunStatus"}
        for value, valid in (
            ({"status": "queued"}, True),
            ({"status": "running", "startedAt": "start"}, True),
            ({"status": "running"}, False),
            ({"status": "queued", "completedAt": "end"}, False),
            ({"status": "succeeded", "startedAt": "start", "completedAt": "end", "result": "result"}, True),
            ({"status": "succeeded", "startedAt": "start", "completedAt": "end"}, False),
            ({"status": "failed", "completedAt": "end", "failure": "error"}, True),
        ):
            with self.subTest(value=value):
                validator = Validator(document, 42, Path(__file__).resolve().parents[2])
                validator.validate_schema_value(value, reference, "generation example")
                self.assertEqual(valid, not validator.errors, validator.errors)

    def test_generation_mode_preserves_historical_inventory(self):
        """생성 연결 모드는 기존 38개를 보존하고 조건·접수·조회·적용 네 개만 추가한다."""
        historical = operations_for_mode(38)
        current = operations_for_mode(42)
        self.assertEqual(38, len(historical))
        self.assertEqual(42, len(current))
        self.assertEqual(historical, {key: current[key] for key in historical})
        prefix = "/api/v1/trips/{tripId}/schedule-generations"
        self.assertEqual("createScheduleGeneration", current[("POST", prefix)])
        self.assertEqual("getScheduleGeneration", current[("GET", prefix + "/{runId}")])
        self.assertEqual(
            "applyScheduleGenerationCandidate",
            current[("POST", prefix + "/{runId}/candidates/{candidateId}/apply")],
        )

    def test_generation_write_headers_are_mandatory(self):
        """생성 접수와 후보 적용은 멱등·충돌 요청 헤더와 재생 응답 헤더를 검증한다."""
        prefix = "/api/v1/trips/{tripId}/schedule-generations"
        apply = prefix + "/{runId}/candidates/{candidateId}/apply"
        for path in (prefix, apply):
            self.assertEqual(
                {"If-Match", "Idempotency-Key"}, REQUIRED_REQUEST_HEADERS.get(("POST", path))
            )
        self.assertEqual(
            {"Location", "Retry-After", "Idempotency-Replayed"},
            REQUIRED_RESPONSE_HEADERS.get(("POST", prefix, "202")),
        )
        self.assertEqual(
            {"ETag", "Location", "Idempotency-Replayed"},
            REQUIRED_RESPONSE_HEADERS.get(("POST", apply, "200")),
        )

    def test_planner_conditions_requires_concurrency_and_replay_headers(self):
        """저장 요청은 충돌·멱등 헤더를 요구하고 응답은 최신 ETag와 replay 여부를 제공한다."""
        path = "/api/v1/trips/{tripId}/planner-conditions"
        self.assertEqual({"If-Match", "Idempotency-Key"}, REQUIRED_REQUEST_HEADERS.get(("PUT", path)))
        self.assertEqual({"ETag", "Idempotency-Replayed"}, REQUIRED_RESPONSE_HEADERS.get(("PUT", path, "200")))


if __name__ == "__main__":
    unittest.main()
