import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CUSTOMIZER = ROOT / "services/spring-api/src/main/java/com/timingjeju/api/global/config/FrontendOpenApiCustomizer.java"
REPOSITORY_TEST = ROOT / "services/spring-api/src/test/java/com/timingjeju/api/global/weather/JdbcWeatherForecastRepositoryIntegrationTest.java"


class Issue222MergeResolutionTest(unittest.TestCase):
    def test_weather_and_transport_problem_examples_survive_develop_merge(self):
        source = CUSTOMIZER.read_text(encoding="utf-8")
        self.assertNotRegex(source, r"(?m)^(<<<<<<<|=======|>>>>>>>)")
        self.assertIn("operationProblems(operationKey, String.valueOf(status))", source)
        self.assertIn('"GET /api/v1/weather/forecast".equals(operationKey)', source)
        self.assertIn('"PUT /api/v1/trips/{tripId}/transport-event".equals(operationKey)', source)
        self.assertIn("WEATHER_LOCATION_NOT_SUPPORTED", source)
        self.assertIn("IDEMPOTENCY_KEY_REUSED", source)

    def test_stale_anchor_checks_and_location_facts_rejection_survive_merge(self):
        source = REPOSITORY_TEST.read_text(encoding="utf-8")
        self.assertNotRegex(source, r"(?m)^(<<<<<<<|=======|>>>>>>>)")
        method = re.search(
            r"void 소유_계획만_공개_장소로_해석하고_JSON_위치로_우회하지_않는다\(\) \{(.*?)\n  \}",
            source,
            re.DOTALL,
        )
        self.assertIsNotNone(method)
        body = method.group(1)
        self.assertIn('set stale=true where id=?', body)
        self.assertIn('set stale=false,stale_at=now() where id=?', body)
        self.assertIn('set stale_at=now()+interval \'1 hour\' where id=?', body)
        self.assertIn('update public.trip_items set place_id=null where id=?', body)
        self.assertIn('schedule item facts must satisfy the closed non-location contract', body)
        self.assertNotIn('set place_id=null,facts=', body)


if __name__ == "__main__":
    unittest.main()
