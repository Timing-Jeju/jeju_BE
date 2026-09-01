import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class FrontendApiClientGenerationTest(unittest.TestCase):
    def test_generator는_검증된_21_operation과_고정_codegen_version만_사용한다(self):
        """생성기는 mode 21 검증과 고정 버전 codegen 뒤 장소 선호 operation을 확인한다."""
        script = (ROOT / "scripts/generate_frontend_api_client.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn(
            'validate_openapi_frontend_readiness.py" "${OPENAPI_PATH}" --mode 21',
            script,
        )
        self.assertIn("typescript@6.0.3", script)
        self.assertIn("@hey-api/openapi-ts@0.99.0", script)
        self.assertIn("len(operation_ids) != 21", script)
        self.assertIn('"tripPlacePreferencesUpdate"', script)

    def test_handoff는_FE_무수정과_release_artifact_비노출_경계를_명시한다(self):
        """FE 인계 문서는 소스 무수정과 release artifact 및 민감정보 비노출을 고정한다."""
        handoff = (ROOT / "docs/TRIP_PLACE_PREFERENCES_API.md").read_text(
            encoding="utf-8"
        )

        self.assertIn("FE 소스 변경: 없음", handoff)
        self.assertIn("timing-jeju-frontend-api-client.tgz", handoff)
        self.assertIn("FE 저장소에 자동 복사하지 않는다", handoff)
        self.assertIn("TMAP 원문·geometry", handoff)


if __name__ == "__main__":
    unittest.main()
