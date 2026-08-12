import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class ExternalApiConfigurationTest(unittest.TestCase):
    PROVIDERS = ("TOUR_API", "TAGO", "TMAP", "KMA")

    def test_env_example_documents_every_provider_without_real_secret(self):
        env_example = (ROOT / ".env.example").read_text(encoding="utf-8")
        for provider in self.PROVIDERS:
            for suffix in ("ENABLED", "API_KEY", "BASE_URL", "CONNECT_TIMEOUT", "READ_TIMEOUT"):
                self.assertIn(f"{provider}_{suffix}=", env_example)
        self.assertNotIn("serviceKey=", env_example)
        self.assertNotIn("Authorization=", env_example)

    def test_compose_files_wire_flags_but_keep_default_ci_providers_disabled(self):
        compose = (ROOT / "compose.yml").read_text(encoding="utf-8")
        compose_test = (ROOT / "compose.test.yml").read_text(encoding="utf-8")
        for provider in self.PROVIDERS:
            self.assertIn(f"{provider}_ENABLED:", compose)
            self.assertIn(f"{provider}_API_KEY:", compose)
            self.assertIn(f"{provider}_BASE_URL:", compose)
            self.assertIn(f"{provider}_ENABLED: \"false\"", compose_test)
        self.assertNotIn("serviceKey", compose)
        self.assertNotIn("Authorization", compose)

    def test_docs_fix_decoded_service_key_and_tmap_header_contract(self):
        configuration = (ROOT / "docs" / "EXTERNAL_API_CONFIGURATION.md").read_text(
            encoding="utf-8"
        )
        mapping = (ROOT / "docs" / "designs" / "timing-jeju-external-api-mapping.md").read_text(
            encoding="utf-8"
        )
        env_example = (ROOT / ".env.example").read_text(encoding="utf-8")

        for phrase in (
            "decoded 원문 key",
            "정확히 한 번 percent-encoding",
            "TMAP_API_KEY",
            "header 원문",
        ):
            self.assertIn(phrase, configuration)
        self.assertIn("decoded 원문", env_example)
        self.assertNotIn("<encoded-key>", mapping)
        self.assertIn("<percent-encoded-decoded-service-key>", mapping)


if __name__ == "__main__":
    unittest.main()
