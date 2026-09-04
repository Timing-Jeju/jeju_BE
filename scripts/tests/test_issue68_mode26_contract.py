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

    def test_cli_and_active_gates_select_mode27(self):
        result = subprocess.run(
            ["python3", str(VALIDATOR), "--help"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertIn("27", result.stdout)
        for path in ("scripts/quality-gate.sh", "scripts/quality-gate.ps1"):
            self.assertIn("--mode 27", (ROOT / path).read_text(encoding="utf-8"), path)


if __name__ == "__main__":
    unittest.main()
