import pathlib
import json
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class FrontendApiClientGenerationTest(unittest.TestCase):
    def test_generator는_검증된_27_operation과_고정_codegen_version만_사용한다(self):
        script = (ROOT / "scripts/generate_frontend_api_client.sh").read_text(encoding="utf-8")
        verifier = (ROOT / "scripts/verify_frontend_api_client_artifact.py").read_text(encoding="utf-8")
        generated_contract = script + verifier

        self.assertIn('validate_openapi_frontend_readiness.py" "${OPENAPI_PATH}" --mode 27', script)
        self.assertIn("typescript@6.0.3", script)
        self.assertIn("@hey-api/openapi-ts@0.99.0", script)
        self.assertIn('verify_frontend_api_client_artifact.py', script)
        self.assertIn('"tripScheduleRead"', generated_contract)
        self.assertIn('"tripScheduleItemCreate"', generated_contract)
        self.assertIn('"tripAccommodationsCreate"', generated_contract)
        self.assertIn('"tripAccommodationsUpdate"', generated_contract)
        self.assertIn('"tripAccommodationsDelete"', generated_contract)

    def test_network_free_verifier는_27_operation_artifact와_index를_검증한다(self):
        verifier = ROOT / "scripts/verify_frontend_api_client_artifact.py"
        operations = [f"operation{index}Read" for index in range(22)] + [
            "tripScheduleRead",
            "tripScheduleItemCreate",
            "tripAccommodationsCreate",
            "tripAccommodationsUpdate",
            "tripAccommodationsDelete",
        ]
        artifact = {
            "paths": {
                f"/test/{index}": {"get": {"operationId": operation}}
                for index, operation in enumerate(operations)
            }
        }
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            openapi = root / "openapi.json"
            output = root / "client"
            output.mkdir()
            openapi.write_text(json.dumps(artifact), encoding="utf-8")
            (output / "index.ts").write_text(
                "\n".join(f"export const {operation} = true;" for operation in operations),
                encoding="utf-8",
            )

            result = subprocess.run(
                ["python3", str(verifier), str(openapi), str(output), "27"],
                cwd=ROOT,
                capture_output=True,
                text=True,
            )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("27 operations", result.stdout)

    def test_handoff는_FE_무수정과_release_artifact_경계를_명시한다(self):
        handoff = (ROOT / "docs/ACCOMMODATION_API.md").read_text(encoding="utf-8")

        self.assertIn("FE 소스 변경: 없음", handoff)
        self.assertIn("timing-jeju-frontend-api-client.tgz", handoff)
        self.assertIn("FE 저장소에 자동 복사하지 않는다", handoff)
        self.assertIn("TMAP 원문·geometry", handoff)


if __name__ == "__main__":
    unittest.main()
