import importlib.util
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts/validate_openapi_frontend_readiness.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("issue68_mode27_validator", VALIDATOR)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class Issue68Mode27ContractTest(unittest.TestCase):
    def test_accommodation_problem_examples_are_named_and_exact(self):
        validator_module = load_validator()
        validator = validator_module.Validator({}, 27, ROOT)
        pairs = {
            ("TRIP_NOT_FOUND", "https://api.timing-jeju.com/problems/trip-not-found"),
            ("PLACE_NOT_FOUND", "https://api.timing-jeju.com/problems/place-not-found"),
        }
        media = {
            "examples": {
                code: {"value": {"code": code, "status": 404, "type": problem_type}}
                for code, problem_type in pairs
            }
        }

        validator.validate_named_problem_examples(
            media, ["TRIP_NOT_FOUND", "PLACE_NOT_FOUND"], pairs, 404, "POST accommodations"
        )
        self.assertFalse(validator.errors, validator.errors)

        media["example"] = None
        media["examples"].pop("PLACE_NOT_FOUND")
        validator.validate_named_problem_examples(
            media, ["TRIP_NOT_FOUND", "PLACE_NOT_FOUND"], pairs, 404, "POST accommodations"
        )
        self.assertTrue(any("단일 Problem example" in error for error in validator.errors))
        self.assertTrue(any("canonical matrix" in error for error in validator.errors))

    def test_mode27_is_current_mode24_plus_accommodation_crud(self):
        validator = load_validator()

        self.assertEqual(
            validator.operations_for_mode(24) | validator.ACCOMMODATION_OPERATIONS,
            validator.operations_for_mode(27),
        )
        self.assertEqual(27, len(validator.operations_for_mode(27)))
        self.assertEqual(
            validator.SOURCE_PROVENANCE_23 | {"accommodations": validator.ACCOMMODATION_SOURCE},
            validator.source_provenance_for_mode(27),
        )

    def test_cli_preserves_mode27_and_active_gates_select_mode31(self):
        result = subprocess.run(
            ["python3", str(VALIDATOR), "--help"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertIn("27", result.stdout)
        self.assertIn("29", result.stdout)
        self.assertIn("30", result.stdout)
        for path in ("scripts/quality-gate.sh", "scripts/quality-gate.ps1"):
            gate = (ROOT / path).read_text(encoding="utf-8")
            self.assertIn("--mode 31", gate, path)


if __name__ == "__main__":
    unittest.main()
