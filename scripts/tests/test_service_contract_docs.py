from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
INTEGRATION_CONTRACT = (
    ROOT / "docs/designs/timing-jeju-spring-fastapi-integration-contract.md"
)


class ServiceContractDocsTest(unittest.TestCase):
    def test_backend_integration_contract_has_required_wire_terms(self):
        required_terms = (
            "POST /mcp",
            "0.7.0",
            "commandInputHash",
            "mcpInputHash",
            "structuredContent",
        )

        document = INTEGRATION_CONTRACT.read_text(encoding="utf-8")
        for term in required_terms:
            with self.subTest(term=term):
                self.assertIn(term, document)

    def test_integration_contract_links_to_ai_repository_contract(self):
        document = INTEGRATION_CONTRACT.read_text(encoding="utf-8")

        self.assertIn("https://github.com/Timing-Jeju/jeju_AI", document)
        self.assertIn("docs/FASTAPI_MCP_CONTRACT.md", document)

    def test_integration_contract_defines_private_health_and_identity(self):
        document = INTEGRATION_CONTRACT.read_text(encoding="utf-8")

        for term in (
            "`/health`",
            "`/ready`",
            "audience: timing-jeju-mcp",
            "scope: jeju:mcp:invoke",
            "Actuator health",
        ):
            with self.subTest(term=term):
                self.assertIn(term, document)


if __name__ == "__main__":
    unittest.main()
