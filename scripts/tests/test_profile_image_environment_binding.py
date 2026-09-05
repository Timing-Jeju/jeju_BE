from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
APPLICATION_YML = ROOT / "services" / "spring-api" / "src" / "main" / "resources" / "application.yml"


class ProfileImageEnvironmentBindingContractTest(unittest.TestCase):
    def test_standard_supabase_environment_is_mapped_to_profile_image_properties(self) -> None:
        source = APPLICATION_YML.read_text(encoding="utf-8")

        self.assertIn("profile-image:", source)
        self.assertIn("supabase-url: ${SUPABASE_URL:}", source)
        self.assertIn("service-role-key: ${SUPABASE_SERVICE_ROLE_KEY:}", source)
        self.assertIn("connect-timeout: ${PROFILE_IMAGE_CONNECT_TIMEOUT:2s}", source)
        self.assertIn("read-timeout: ${PROFILE_IMAGE_READ_TIMEOUT:5s}", source)


if __name__ == "__main__":
    unittest.main()
