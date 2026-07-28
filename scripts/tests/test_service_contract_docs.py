from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FASTAPI_CONTRACT = ROOT / "docs/designs/timing-jeju-fastapi-mcp-contract.md"
INTEGRATION_CONTRACT = (
    ROOT / "docs/designs/timing-jeju-spring-fastapi-integration-contract.md"
)


class ServiceContractDocsTest(unittest.TestCase):
    def test_both_contracts_share_required_wire_terms(self):
        required_terms = ("POST /mcp", "contractVersion", "inputHash")

        for path in (FASTAPI_CONTRACT, INTEGRATION_CONTRACT):
            document = path.read_text(encoding="utf-8")
            for term in required_terms:
                with self.subTest(document=path.name, term=term):
                    self.assertIn(term, document)

    def test_integration_contract_defines_private_health_and_identity(self):
        document = INTEGRATION_CONTRACT.read_text(encoding="utf-8")

        for term in ("/health/live", "/health/ready", "aud=timing-jeju-fastapi"):
            with self.subTest(term=term):
                self.assertIn(term, document)


if __name__ == "__main__":
    unittest.main()
