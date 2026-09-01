from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "fixtures/tmap-route-poc/golden-matrix.json"


class TmapRoutePocContractTest(unittest.TestCase):
    def test_제주_AI_기준의_10개_구간과_세_모드_비저장_경계를_고정한다(self):
        """제주 AI의 승인 공급자와 TMAP 비저장 경계를 열 개 대표 구간에 고정한다."""
        payload = json.loads(MANIFEST.read_text(encoding="utf-8"))

        routes = payload["goldenRoutes"]
        self.assertEqual(10, len(routes))
        self.assertEqual(10, len({route["id"] for route in routes}))
        self.assertTrue(
            all(
                set(route["modes"])
                == {"PEDESTRIAN", "DRIVING", "PUBLIC_TRANSIT"}
                for route in routes
            )
        )

        policy = payload["providerPolicy"]
        self.assertEqual("tmap.pedestrian", policy["PEDESTRIAN"]["sourceId"])
        self.assertEqual("tmap.driving", policy["DRIVING"]["sourceId"])
        self.assertEqual(
            "OFFICIAL_TIMETABLE_AND_TAGO",
            policy["PUBLIC_TRANSIT"]["providerBoundary"],
        )
        self.assertFalse(policy["PUBLIC_TRANSIT"]["tmapCallAllowed"])

        persistence = payload["persistencePolicy"]
        self.assertEqual("MEMORY_ONLY_LT_24H", persistence["retention"])
        self.assertFalse(persistence["rawBody"])
        self.assertFalse(persistence["geometry"])
        self.assertFalse(persistence["routeMetrics"])
        self.assertFalse(persistence["requestUrlOrQuery"])
        self.assertFalse(persistence["userLocationHistory"])
        self.assertLessEqual(persistence["maximumTtlMinutes"], 23 * 60 + 50)

        serialized = json.dumps(payload, ensure_ascii=False).lower()
        for forbidden in (
            "durationseconds",
            "distancemeters",
            "farekrw",
            "polyline",
            "authorization",
            "api_key",
        ):
            self.assertNotIn(forbidden, serialized)

        self.assertEqual("DEFER", payload["decision"]["recommendation"])
        self.assertEqual(
            "PROVIDER_NEUTRAL_WITH_TMAP_DISABLED_BY_DEFAULT",
            payload["decision"]["issue41Boundary"],
        )
        for approval_key in (
            "architectureOwnerApproval",
            "productOwnerApproval",
        ):
            approval = payload["decision"][approval_key]
            self.assertEqual("APPROVED", approval["status"])
            self.assertEqual("kwongwangjae", approval["approvedBy"])
            self.assertEqual("2026-09-01T22:41:52Z", approval["approvedAt"])
            self.assertEqual(
                "https://github.com/Timing-Jeju/jeju_BE/issues/40#issuecomment-5501405118",
                approval["evidenceUrl"],
            )


if __name__ == "__main__":
    unittest.main()
