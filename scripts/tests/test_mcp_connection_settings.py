"""배포 없이 MCP 연결 설정의 외부 주입 계약을 검증한다."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class McpConnectionSettingsTest(unittest.TestCase):
    def test_compose_passes_mcp_settings_without_enabling_them(self):
        """MCP 설정을 API에 전달하되 명시적 활성화 전에는 호출하지 않는다."""
        compose = (ROOT / "compose.yml").read_text()
        api = compose.split("  api:", 1)[1]
        expected = {
            "MCP_ENABLED": "false",
            "MCP_BASE_URL": "https://timing-jeju-ai:8000",
            "MCP_ALLOWED_HOST": "timing-jeju-ai",
            "MCP_JWT_ISSUER": "timing-jeju-spring",
            "MCP_JWT_AUDIENCE": "timing-jeju-mcp",
            "MCP_JWT_SIGNING_KEY_DESCRIPTOR_FILE": "",
            "MCP_TLS_TRUST_CERTIFICATE_FILE": "",
            "MCP_JWT_LIFETIME": "2m",
            "MCP_REQUEST_TIMEOUT": "35s",
            "MCP_GENERATION_REQUEST_TIMEOUT": "165s",
            "MCP_MAX_ATTEMPTS": "3",
            "MCP_RETRY_DELAY": "200ms",
            "MCP_CIRCUIT_FAILURE_THRESHOLD": "5",
            "MCP_CIRCUIT_OPEN_DURATION": "30s",
        }
        for name, default in expected.items():
            with self.subTest(setting=name):
                self.assertIn(f"      {name}: ${{{name}:-{default}}}", api)

    def test_optional_tls_property_is_documented_and_bound(self):
        """PEM 신뢰 파일은 빈 기본값으로 연결되며 배포 인계 문서가 존재한다."""
        config = (ROOT / "services/spring-api/src/main/resources/application.yml").read_text()
        self.assertIn("tls-trust-certificate-file: ${MCP_TLS_TRUST_CERTIFICATE_FILE:}", config)
        self.assertIn("MCP_TLS_TRUST_CERTIFICATE_FILE=", (ROOT / ".env.example").read_text())
        self.assertTrue((ROOT / "docs/MCP_CONNECTION_SETUP.md").is_file())
