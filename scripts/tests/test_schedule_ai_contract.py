import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs" / "contracts" / "domains" / "schedule-ai" / "contract.json"
CATALOG = ROOT / "docs" / "contracts" / "rest" / "catalog.json"

EXPECTED_ENDPOINTS = {
    ("POST", "/api/v1/trips/{tripId}/generation-runs"),
    ("GET", "/api/v1/trips/{tripId}/generation-runs/{runId}"),
    (
        "POST",
        "/api/v1/trips/{tripId}/generation-runs/{runId}/candidates/{candidateId}/apply",
    ),
    ("POST", "/api/v1/trips/{tripId}/schedule-revision-runs"),
    ("GET", "/api/v1/trips/{tripId}/schedule-revision-runs/{runId}"),
    (
        "POST",
        "/api/v1/trips/{tripId}/schedule-revision-runs/{runId}/candidates/{candidateId}/apply",
    ),
}


class ScheduleAiContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        self.catalog = json.loads(CATALOG.read_text(encoding="utf-8"))

    def test_six_endpoint_identities_are_exact_and_unique(self) -> None:
        actual = [(item["method"], item["path"]) for item in self.contract["endpoints"]]
        self.assertEqual(EXPECTED_ENDPOINTS, set(actual))
        self.assertEqual(len(EXPECTED_ENDPOINTS), len(actual))

    def test_async_lifecycle_and_candidate_expiry_are_explicit(self) -> None:
        self.assertEqual(
            ["queued", "running", "succeeded", "failed", "cancelled"],
            self.contract["lifecycle"]["states"],
        )
        self.assertEqual("candidate.expiresAt", self.contract["lifecycle"]["expiryField"])
        for endpoint in self.contract["endpoints"]:
            self.assertIn("pollUrl", endpoint["responseFields"])

    def test_owners_and_db_discriminators_are_explicit_without_schema_mutation(self) -> None:
        owners = self.contract["implementationOwners"]
        self.assertEqual(53, owners["generationIntake"])
        self.assertEqual(79, owners["generationWorkerAndCandidate"])
        self.assertEqual(69, owners["revisionIntake"])
        self.assertEqual(104, owners["revisionWorkerAndCandidate"])
        self.assertEqual(108, owners["commandSnapshotMigration"])
        self.assertFalse(self.contract["databasePolicy"]["schemaChangedByIssue89"])

    def test_external_readiness_is_fail_closed_without_authoritative_evidence(self) -> None:
        for system in ("notion", "figma"):
            trace = self.contract["externalTraceability"][system]
            self.assertEqual("not-linked", trace["contractVersion"])
            self.assertEqual("not-ready", trace["status"])
            self.assertIsNone(trace["evidence"])

        domain = next(item for item in self.catalog["domainContracts"] if item["issue"] == 89)
        self.assertEqual(
            {"local": "1.0.0", "notion": "not-linked", "figma": "not-linked"},
            domain["versions"],
        )
        for readiness in domain["readiness"].values():
            self.assertEqual({"status": "not-ready", "evidence": None}, readiness)


if __name__ == "__main__":
    unittest.main()
