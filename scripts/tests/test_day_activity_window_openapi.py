"""Day 활동창 공개 operation을 기존 FE 계약에 추가한다."""
import unittest
from scripts.validate_openapi_frontend_readiness import operations_for_mode


class DayActivityWindowOpenApiTest(unittest.TestCase):
    def test_mode38_adds_one_operation_and_preserves_mode33(self):
        """38개 모드는 기존 모드의 37개 operation과 활동창 PUT을 정확히 포함한다."""
        previous = operations_for_mode(33)
        current = operations_for_mode(38)
        self.assertEqual(37, len(previous))
        self.assertEqual(38, len(current))
        self.assertEqual(previous.items(), {k: v for k, v in current.items() if k in previous}.items())
        self.assertEqual("tripDayActivityWindowsUpdate", current[("PUT", "/api/v1/trips/{tripId}/day-activity-windows")])
