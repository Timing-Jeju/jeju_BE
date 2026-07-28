from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "validate-test-pair.py"
SPEC = importlib.util.spec_from_file_location("validate_test_pair", MODULE_PATH)
assert SPEC is not None
assert SPEC.loader is not None
validate_test_pair = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validate_test_pair)


class ValidateTestPairTest(unittest.TestCase):
    def test_spring_production_change_without_test_is_blocked(self):
        ok, _ = validate_test_pair.validate(
            "feat/14-place-search",
            ["services/spring-api/src/main/java/com/timingjeju/api/Place.java"],
        )

        self.assertFalse(ok)

    def test_spring_production_and_test_change_are_allowed(self):
        ok, _ = validate_test_pair.validate(
            "feat/14-place-search",
            [
                "services/spring-api/src/main/java/com/timingjeju/api/Place.java",
                "services/spring-api/src/test/java/com/timingjeju/api/PlaceTest.java",
            ],
        )

        self.assertTrue(ok)

    def test_obsolete_root_src_path_is_not_treated_as_spring_code(self):
        ok, _ = validate_test_pair.validate(
            "feat/14-place-search",
            ["src/main/java/com/timingjeju/api/Place.java"],
        )

        self.assertTrue(ok)


if __name__ == "__main__":
    unittest.main()
