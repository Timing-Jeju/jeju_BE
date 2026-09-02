from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from scripts.tmap_route_poc import (
    ContractViolation,
    aggregate_observations,
    build_requests,
    classify_failure,
    execute_live_matrix,
    live_preflight,
    sanitize_response,
)


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "fixtures/tmap-route-poc/golden-matrix.json"
DETERMINISTIC_RESPONSES = (
    ROOT / "fixtures/tmap-route-poc/deterministic-provider-responses.json"
)
DESIGN = ROOT / "docs/designs/timing-jeju-external-api-mapping.md"
DB_DESIGN = ROOT / "docs/designs/timing-jeju-db-schema-v0.md"
VALIDATION_CHECKLIST = (
    ROOT / "docs/designs/timing-jeju-backend-validation-checklist.md"
)
EXTERNAL_API_CONFIGURATION = ROOT / "docs/EXTERNAL_API_CONFIGURATION.md"
ENV_EXAMPLE = ROOT / ".env.example"


class TmapRoutePocContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.payload = json.loads(MANIFEST.read_text(encoding="utf-8"))

    def test_공개_대표좌표와_출발시각으로_정확히_30개_요청을_만든다(self):
        """열 개 공개 장소 구간과 세 모드의 실행 가능한 요청 조합을 고정한다."""
        requests = build_requests(self.payload)

        self.assertEqual(30, len(requests))
        self.assertEqual(
            30,
            len(
                {
                    (request["caseId"], request["mode"], request["departureAt"])
                    for request in requests
                }
            ),
        )
        for request in requests:
            self.assertEqual("EPSG:4326", request["crs"])
            self.assertEqual("LONGITUDE_LATITUDE", request["coordinateOrder"])
            for endpoint in (request["origin"], request["destination"]):
                self.assertGreaterEqual(endpoint["latitude"], 33.0)
                self.assertLessEqual(endpoint["latitude"], 34.0)
                self.assertGreaterEqual(endpoint["longitude"], 126.0)
                self.assertLessEqual(endpoint["longitude"], 127.0)
                self.assertEqual(
                    "PUBLIC_PLACE_REPRESENTATIVE_POINT", endpoint["basis"]
                )

    def test_응답은_수치와_geometry를_버리고_필드_가용성만_남긴다(self):
        """공급자 원문과 개별 경로 수치 없이 필드 존재 여부만 증거로 정규화한다."""
        observation = sanitize_response(
            case_id="airport-to-seongsan",
            mode="DRIVING",
            departure_at="2026-09-15T09:00:00+09:00",
            response={
                "durationSeconds": 3600,
                "distanceMeters": 47000,
                "fareKrw": None,
                "walkSegments": [],
                "geometry": "provider-linestring",
            },
        )

        self.assertEqual("SUCCESS", observation["status"])
        self.assertTrue(observation["fieldAvailability"]["duration"])
        self.assertTrue(observation["fieldAvailability"]["distance"])
        self.assertFalse(observation["fieldAvailability"]["fare"])
        self.assertFalse(observation["fieldAvailability"]["walkSegment"])
        self.assertTrue(observation["fieldAvailability"]["polyline"])
        serialized = json.dumps(observation, ensure_ascii=False).lower()
        for forbidden in (
            "3600",
            "47000",
            "provider-linestring",
            "durationseconds",
            "distancemeters",
            "geometry",
        ):
            self.assertNotIn(forbidden, serialized)

    def test_제주_밖_좌표와_과거_과도한_미래_출발시각을_거부한다(self):
        """제주 bounds와 승인 출발시각 구간을 벗어난 golden request를 선거부한다."""
        outside_jeju = copy.deepcopy(self.payload)
        outside_jeju["goldenRoutes"][0]["origin"]["latitude"] = 37.5665
        with self.assertRaisesRegex(ContractViolation, "OUTSIDE_JEJU_BOUNDS"):
            build_requests(outside_jeju)

        for departure_at in (
            "2026-09-01T23:59:59+09:00",
            "2026-10-03T00:00:00+09:00",
        ):
            invalid_time = copy.deepcopy(self.payload)
            invalid_time["requestContract"]["departureAt"] = departure_at
            with self.subTest(departure_at=departure_at):
                with self.assertRaisesRegex(
                    ContractViolation, "DEPARTURE_OUTSIDE_APPROVED_WINDOW"
                ):
                    build_requests(invalid_time)

    def test_승인_source_host_path와_대중교통_경계를_벗어나면_거부한다(self):
        """TMAP source allowlist와 공식 대중교통 경계를 manifest 조작으로 완화하지 못한다."""
        invalid_host = copy.deepcopy(self.payload)
        invalid_host["providerPolicy"]["DRIVING"]["allowedHost"] = "example.com"
        with self.assertRaisesRegex(
            ContractViolation, "UNAPPROVED_TMAP_SOURCE_CONTRACT"
        ):
            build_requests(invalid_host)

        invalid_transit = copy.deepcopy(self.payload)
        invalid_transit["providerPolicy"]["PUBLIC_TRANSIT"][
            "tmapCallAllowed"
        ] = True
        with self.assertRaisesRegex(
            ContractViolation, "TMAP_PUBLIC_TRANSIT_MUST_REMAIN_DISABLED"
        ):
            build_requests(invalid_transit)

    def test_quota와_timeout을_원문_없이_안정적인_code로_분류한다(self):
        """쿼터와 timeout 실패를 provider 메시지 없이 안정적인 reason code로 바꾼다."""
        self.assertEqual("QUOTA_EXCEEDED", classify_failure(429, None))
        self.assertEqual("TIMEOUT", classify_failure(None, TimeoutError("secret")))
        self.assertEqual("PROVIDER_UNAVAILABLE", classify_failure(503, None))

    def test_잘못된_응답과_비유한_route_field를_거부한다(self):
        """응답 객체·route field 타입과 NaN·무한대 값을 안전하게 거부한다."""
        invalid_responses = (
            None,
            [],
            "provider-raw-secret",
            {"durationSeconds": True},
            {"distanceMeters": "100"},
            {"fareKrw": -1},
            {"durationSeconds": float("nan")},
            {"distanceMeters": float("inf")},
            {"fareKrw": float("-inf")},
            {"walkSegments": {}},
            {"geometry": 123},
        )
        for response in invalid_responses:
            with self.subTest(response=response):
                with self.assertRaisesRegex(ContractViolation, "MALFORMED_RESPONSE"):
                    sanitize_response(
                        case_id="airport-to-seongsan",
                        mode="DRIVING",
                        departure_at="2026-09-15T09:00:00+09:00",
                        response=response,
                    )

    def test_30개_observation에서만_집계를_재생성한다(self):
        """case·mode·departure가 정확한 30개 증거일 때만 집계를 생성한다."""
        requests = build_requests(self.payload)
        fixture = json.loads(DETERMINISTIC_RESPONSES.read_text(encoding="utf-8"))
        self.assertEqual("SYNTHETIC_CONTRACT_ONLY", fixture["fixtureKind"])
        self.assertTrue(fixture["mustNotBeUsedAsLiveEvidence"])
        observations = [
            sanitize_response(
                case_id=request["caseId"],
                mode=request["mode"],
                departure_at=request["departureAt"],
                response=fixture["responsesByMode"][request["mode"]],
            )
            for request in requests
        ]

        aggregate = aggregate_observations(requests, observations)
        self.assertEqual(30, aggregate["total"])
        self.assertEqual(30, aggregate["statusCounts"]["SUCCESS"])
        self.assertEqual(10, aggregate["fieldAvailability"]["walkSegment"])
        for mode in ("PEDESTRIAN", "DRIVING", "PUBLIC_TRANSIT"):
            for field in ("duration", "distance", "fare", "walkSegment", "polyline"):
                mode_field = aggregate["fieldAvailabilityByMode"][mode][field]
                self.assertEqual(10, mode_field["available"] + mode_field["missing"])
        self.assertEqual(
            0,
            aggregate["fieldAvailabilityByMode"]["PEDESTRIAN"]["walkSegment"][
                "available"
            ],
        )
        self.assertEqual(
            10,
            aggregate["fieldAvailabilityByMode"]["PUBLIC_TRANSIT"]["walkSegment"][
                "available"
            ],
        )

        for invalid in (
            observations[:-1],
            observations + [copy.deepcopy(observations[0])],
        ):
            with self.subTest(count=len(invalid)):
                with self.assertRaises(ContractViolation):
                    aggregate_observations(requests, invalid)

        mismatch = copy.deepcopy(observations)
        mismatch[0]["departureAt"] = "2026-09-15T10:00:00+09:00"
        with self.assertRaises(ContractViolation):
            aggregate_observations(requests, mismatch)

    def test_키가_없으면_live_runner가_명시적인_skip을_반환한다(self):
        """승인된 TMAP 키가 없을 때 네트워크 호출 없이 명시적 skip 사유를 남긴다."""
        result = live_preflight(self.payload, {})

        self.assertEqual("SKIPPED", result["status"])
        self.assertEqual("APPROVED_TMAP_KEY_NOT_PRESENT", result["reasonCode"])
        self.assertEqual(0, result["plannedNetworkCalls"])

    def test_문서화된_PoC_키가_있을_때만_live_preflight가_READY다(self):
        """예시 환경의 FastAPI 전용 키 이름과 preflight 활성 조건을 일치시킨다."""
        env_example = ENV_EXAMPLE.read_text(encoding="utf-8")
        self.assertIn("JEJU_TMAP_API_KEY=", env_example)

        result = live_preflight(
            self.payload,
            {"JEJU_TMAP_API_KEY": "test-secret-not-logged"},
        )

        self.assertEqual("READY", result["status"])
        self.assertEqual(20, result["plannedNetworkCalls"])

    def test_조건부_live_runner는_승인_transport로_30건을_집계한다(self):
        """승인 키가 있을 때 TMAP 20건과 공식 대중교통 10건을 값 없이 집계한다."""
        fixture = json.loads(DETERMINISTIC_RESPONSES.read_text(encoding="utf-8"))
        tmap_calls: list[str] = []
        transit_calls: list[str] = []

        def tmap_transport(request, api_key):
            self.assertEqual("test-secret-not-logged", api_key)
            tmap_calls.append(request["mode"])
            return fixture["responsesByMode"][request["mode"]]

        def official_transit_transport(request):
            transit_calls.append(request["mode"])
            return fixture["responsesByMode"][request["mode"]]

        result = execute_live_matrix(
            self.payload,
            {"JEJU_TMAP_API_KEY": "test-secret-not-logged"},
            tmap_transport=tmap_transport,
            official_transit_transport=official_transit_transport,
        )

        self.assertEqual("COMPLETED", result["status"])
        self.assertEqual(30, result["aggregate"]["total"])
        self.assertEqual(20, len(tmap_calls))
        self.assertEqual(10, len(transit_calls))
        self.assertNotIn(
            "test-secret-not-logged",
            json.dumps(result, ensure_ascii=False),
        )

    def test_예기치_않은_transport_오류도_원문_없이_안전_code로_집계한다(self):
        """예상하지 못한 transport 예외도 실행을 중단하거나 원문을 노출하지 않는다."""
        fixture = json.loads(DETERMINISTIC_RESPONSES.read_text(encoding="utf-8"))

        def failing_tmap_transport(request, api_key):
            raise RuntimeError("provider-raw-secret")

        def official_transit_transport(request):
            return fixture["responsesByMode"][request["mode"]]

        result = execute_live_matrix(
            self.payload,
            {"JEJU_TMAP_API_KEY": "test-secret-not-logged"},
            tmap_transport=failing_tmap_transport,
            official_transit_transport=official_transit_transport,
        )

        self.assertEqual("COMPLETED", result["status"])
        self.assertEqual(
            20,
            result["aggregate"]["reasonCodeCounts"]["TRANSPORT_FAILURE"],
        )
        serialized = json.dumps(result, ensure_ascii=False)
        self.assertNotIn("provider-raw-secret", serialized)
        self.assertNotIn("test-secret-not-logged", serialized)

    def test_DEFER_결정과_canonical_TMAP_저장_경계가_일치한다(self):
        """canonical 설계가 TMAP 대중교통·snapshot 저장을 기본값으로 남기지 않는다."""
        design = DESIGN.read_text(encoding="utf-8")

        self.assertIn("Issue #40 DEFER 결정", design)
        self.assertIn("PROVIDER_NEUTRAL_WITH_TMAP_DISABLED_BY_DEFAULT", design)
        self.assertIn("TMAP 경로 결과는 `mobility_route_snapshots`에 저장하지 않는다", design)
        self.assertIn("대중교통은 공식 시간표와 TAGO", design)

        db_design = DB_DESIGN.read_text(encoding="utf-8")
        self.assertIn("TMAP 저장 금지; #40 DEFER 경계", db_design)
        self.assertIn("TMAP 원문·geometry·개별 route metric", db_design)

        checklist = VALIDATION_CHECKLIST.read_text(encoding="utf-8")
        self.assertIn("| 길찾기 공급자 POC | DEFER |", checklist)
        self.assertIn("TMAP DEFER", checklist)
        self.assertIn("TMAP 보행·자동차 on-demand", checklist)
        self.assertIn("FastAPI OWNER", checklist)

        configuration = EXTERNAL_API_CONFIGURATION.read_text(encoding="utf-8")
        self.assertIn("`TMAP_ENABLED=false`가 canonical 기본값", configuration)
        self.assertIn("`JEJU_TMAP_API_KEY`", configuration)

    def test_Owner_승인과_비저장_경계를_고정한다(self):
        """두 Owner 승인과 TMAP 메모리 전용 보존 경계를 변경 불가능한 계약으로 둔다."""
        persistence = self.payload["persistencePolicy"]
        self.assertEqual("MEMORY_ONLY_LT_24H", persistence["retention"])
        self.assertLessEqual(persistence["maximumTtlMinutes"], 23 * 60 + 50)
        for forbidden_key in (
            "rawBody",
            "geometry",
            "routeMetrics",
            "requestUrlOrQuery",
            "userLocationHistory",
        ):
            self.assertFalse(persistence[forbidden_key])

        self.assertEqual("DEFER", self.payload["decision"]["recommendation"])
        self.assertEqual(
            "PROVIDER_NEUTRAL_WITH_TMAP_DISABLED_BY_DEFAULT",
            self.payload["decision"]["issue41Boundary"],
        )
        for approval_key in (
            "architectureOwnerApproval",
            "productOwnerApproval",
        ):
            approval = self.payload["decision"][approval_key]
            self.assertEqual("APPROVED", approval["status"])
            self.assertEqual("kwongwangjae", approval["approvedBy"])
            self.assertEqual("2026-09-01T22:41:52Z", approval["approvedAt"])


if __name__ == "__main__":
    unittest.main()
