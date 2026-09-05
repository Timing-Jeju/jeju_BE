from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APPLICATION_YML = ROOT / "services" / "spring-api" / "src" / "main" / "resources" / "application.yml"
COMPOSE_YML = ROOT / "compose.yml"
COMPOSE_TEST_YML = ROOT / "compose.test.yml"
ENV_EXAMPLE = ROOT / ".env.example"


class ProfileImageEnvironmentBindingContractTest(unittest.TestCase):
    def test_standard_supabase_environment_is_mapped_to_profile_image_properties(self) -> None:
        source = APPLICATION_YML.read_text(encoding="utf-8")

        self.assertIn("profile-image:", source)
        self.assertIn("supabase-url: ${SUPABASE_URL:}", source)
        self.assertIn("service-role-key: ${SUPABASE_SERVICE_ROLE_KEY:}", source)
        self.assertIn("connect-timeout: ${PROFILE_IMAGE_CONNECT_TIMEOUT:2s}", source)
        self.assertIn("read-timeout: ${PROFILE_IMAGE_READ_TIMEOUT:5s}", source)

        compose = COMPOSE_YML.read_text(encoding="utf-8")
        self.assertIn("SUPABASE_URL: ${SUPABASE_URL:-}", compose)
        self.assertIn("SUPABASE_SERVICE_ROLE_KEY: ${SUPABASE_SERVICE_ROLE_KEY:-}", compose)

        compose_test = COMPOSE_TEST_YML.read_text(encoding="utf-8")
        self.assertIn('SUPABASE_SERVICE_ROLE_KEY: ""', compose_test)
        self.assertIn('PROFILE_IMAGE_MAINTENANCE_ENABLED: "false"', compose_test)

        env_example = ENV_EXAMPLE.read_text(encoding="utf-8")
        self.assertIn("SUPABASE_SERVICE_ROLE_KEY=", env_example)
        self.assertIn("프론트/모바일/로그에 노출하지 않습니다", env_example)


if __name__ == "__main__":
    unittest.main()
