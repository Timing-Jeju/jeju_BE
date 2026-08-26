import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "docs" / "contracts" / "rest" / "catalog.json"
SHELL_GATE = ROOT / "scripts" / "quality-gate.sh"
POWERSHELL_GATE = ROOT / "scripts" / "quality-gate.ps1"

EXPECTED_ENDPOINTS = {
    ("GET", "/api/v1/me"),
    ("PATCH", "/api/v1/me"),
    ("DELETE", "/api/v1/me"),
    ("GET", "/api/v1/legal-documents"),
    ("PUT", "/api/v1/me/consents"),
    ("GET", "/api/v1/account-deletion-requests/{deletionRequestId}"),
    ("GET", "/api/v1/places"),
    ("GET", "/api/v1/places/{placeId}"),
    ("GET", "/api/v1/me/saved-places"),
    ("POST", "/api/v1/me/saved-places"),
    ("PATCH", "/api/v1/me/saved-places/{placeId}"),
    ("DELETE", "/api/v1/me/saved-places/{placeId}"),
    ("GET", "/api/v1/trips"),
    ("POST", "/api/v1/trips"),
    ("GET", "/api/v1/trips/{tripId}"),
    ("PATCH", "/api/v1/trips/{tripId}"),
    ("DELETE", "/api/v1/trips/{tripId}"),
    ("PUT", "/api/v1/trips/{tripId}/preferences"),
    ("PUT", "/api/v1/trips/{tripId}/place-preferences"),
    ("PUT", "/api/v1/trips/{tripId}/transport-event"),
    ("DELETE", "/api/v1/trips/{tripId}/transport-event"),
    ("POST", "/api/v1/trips/{tripId}/accommodations"),
    ("PATCH", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"),
    ("DELETE", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"),
    ("GET", "/api/v1/trips/{tripId}/schedule"),
    ("POST", "/api/v1/trips/{tripId}/schedule-items"),
    ("PATCH", "/api/v1/trips/{tripId}/schedule-items/{itemId}"),
    ("DELETE", "/api/v1/trips/{tripId}/schedule-items/{itemId}"),
    ("PUT", "/api/v1/trips/{tripId}/schedule-order"),
    ("POST", "/api/v1/trips/{tripId}/schedule-items/{itemId}/move"),
    ("GET", "/api/v1/weather/forecast"),
    ("POST", "/api/v1/trips/{tripId}/feasibility-runs"),
    ("GET", "/api/v1/trips/{tripId}/feasibility-runs/{runId}"),
    (
        "GET",
        "/api/v1/trips/{tripId}/schedule-versions/{versionId}/legs/{legId}",
    ),
    ("PUT", "/api/v1/me/push-devices/{deviceId}"),
    ("DELETE", "/api/v1/me/push-devices/{deviceId}"),
    ("GET", "/api/v1/me/notification-preferences"),
    ("PATCH", "/api/v1/me/notification-preferences"),
}
EXPECTED_VALIDATORS = (
    "validate_rest_contracts.py",
    "validate_profile_legal_contract.py",
    "validate_places_contract.py",
    "validate_saved_places_contract.py",
    "validate_trips_contract.py",
    "validate_preferences_transport_contract.py",
    "validate_accommodations_contract.py",
    "validate_schedules_contract.py",
    "validate_weather_forecast_contract.py",
    "validate_feasibility_legs_contract.py",
    "validate_location_retention_contract.py",
)


def _active_commands(source: str, prefix: str) -> set[str]:
    commands = set()
    for line in source.splitlines():
        stripped = line.strip()
        if stripped.startswith("#") or prefix not in stripped:
            continue
        command = stripped[stripped.index(prefix):]
        if command.endswith(" }"):
            command = command[:-2]
        commands.add(command)
    return commands


class ContractSuiteIntegrationTest(unittest.TestCase):
    def test_catalog_preserves_all_places_saved_places_and_trips_endpoints(self) -> None:
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        actual = {(endpoint["method"], endpoint["path"]) for endpoint in catalog["endpoints"]}

        self.assertEqual(EXPECTED_ENDPOINTS, actual)
        self.assertEqual(38, len(catalog["endpoints"]))

    def test_quality_gates_execute_all_contract_validators(self) -> None:
        shell_commands = _active_commands(SHELL_GATE.read_text(encoding="utf-8"), "python3 scripts/")
        powershell_commands = _active_commands(
            POWERSHELL_GATE.read_text(encoding="utf-8"), "py -3 scripts/"
        )

        for validator in EXPECTED_VALIDATORS:
            with self.subTest(gate="shell", validator=validator):
                self.assertIn(f"python3 scripts/{validator}", shell_commands)
            with self.subTest(gate="powershell", validator=validator):
                self.assertIn(f"py -3 scripts/{validator}", powershell_commands)

    def test_commented_validator_commands_are_not_active(self) -> None:
        source = """
        # python3 scripts/validate_rest_contracts.py
        python3 scripts/validate_places_contract.py
        """

        self.assertEqual(
            {"python3 scripts/validate_places_contract.py"},
            _active_commands(source, "python3 scripts/"),
        )


if __name__ == "__main__":
    unittest.main()
