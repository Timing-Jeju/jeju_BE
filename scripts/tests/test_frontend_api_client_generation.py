import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class FrontendApiClientGenerationTest(unittest.TestCase):
    def test_generator는_검증된_27_operation과_고정_codegen_version만_사용한다(self):
        script = (ROOT / "scripts/generate_frontend_api_client.sh").read_text(encoding="utf-8")

        self.assertIn('validate_openapi_frontend_readiness.py" "${OPENAPI_PATH}" --mode 27', script)
        self.assertIn("typescript@6.0.3", script)
        self.assertIn("@hey-api/openapi-ts@0.99.0", script)
        self.assertIn('len(operation_ids) != 27', script)
        self.assertIn('"tripAccommodationsCreate"', script)
        self.assertIn('"tripAccommodationsUpdate"', script)
        self.assertIn('"tripAccommodationsDelete"', script)
        self.assertIn('"tripTransportEventsUpdate"', script)
        self.assertIn('"tripTransportEventsDelete"', script)

    def test_handoff는_FE_무수정과_release_artifact_경계를_명시한다(self):
        handoff = (ROOT / "docs/ACCOMMODATION_API.md").read_text(encoding="utf-8")

        self.assertIn("FE 소스 변경: 없음", handoff)
        self.assertIn("timing-jeju-frontend-api-client.tgz", handoff)
        self.assertIn("FE 저장소에 자동 복사하지 않는다", handoff)
        self.assertIn("TMAP 원문·geometry", handoff)


if __name__ == "__main__":
    unittest.main()
