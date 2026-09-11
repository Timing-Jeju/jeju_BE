from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class AuthenticationProblemContractTest(unittest.TestCase):
    def test_runtime_manifest는_public_social_optional과_required_인증을_분리한다(self):
        manifest = json.loads(
            (ROOT / "scripts/openapi_frontend_runtime_manifest.json").read_text(
                encoding="utf-8"
            )
        )
        definitions = manifest["runtimeProblemDefinitions"]
        self.assertEqual(
            definitions["AUTHENTICATION_REQUIRED"],
            "https://api.timing-jeju.com/problems/authentication-required",
        )
        self.assertEqual(
            definitions["INVALID_ACCESS_TOKEN"],
            "https://api.timing-jeju.com/problems/invalid-access-token",
        )
        self.assertEqual(
            definitions["SOCIAL_NAVER_TOKEN_INVALID"],
            "https://api.timing-jeju.example/problems/social-naver-token-invalid",
        )
        self.assertNotIn("AUTH_TOKEN_INVALID", definitions)

        public_social = {
            "GET /api/v1/auth/social/providers": None,
            "GET /api/v1/auth/social/naver/userinfo": [
                "SOCIAL_NAVER_TOKEN_INVALID",
                "https://api.timing-jeju.example/problems/social-naver-token-invalid",
            ],
        }
        for operation, expected in public_social.items():
            runtime = manifest["operations"][operation]
            self.assertEqual(runtime["problems"].get("401"), expected, operation)

        optional = {
            "GET /api/v1/legal-documents",
            "GET /api/v1/places",
            "GET /api/v1/places/{placeId}",
            "GET /api/v1/weather/forecast",
        }
        for operation, runtime in manifest["operations"].items():
            if operation in public_social:
                continue
            expected = (
                [
                    "INVALID_ACCESS_TOKEN",
                    "https://api.timing-jeju.com/problems/invalid-access-token",
                ]
                if operation in optional
                else [
                    "AUTHENTICATION_REQUIRED",
                    "https://api.timing-jeju.com/problems/authentication-required",
                ]
            )
            self.assertEqual(runtime["problems"].get("401"), expected, operation)

    def test_entry_point는_endpoint나_exception_message가_아닌_header_presence만_분류한다(self):
        entry_point = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/global/security/JsonAuthenticationEntryPoint.java"
        ).read_text(encoding="utf-8")
        writer = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/global/error/AuthenticationProblemWriter.java"
        ).read_text(encoding="utf-8")

        self.assertIn("problemWriter.writeCanonical(request, response)", entry_point)
        self.assertIn("getHeaders(HttpHeaders.AUTHORIZATION).hasMoreElements()", writer)
        self.assertIn("StandardProblemCode.AUTHENTICATION_REQUIRED", writer)
        self.assertIn("StandardProblemCode.INVALID_ACCESS_TOKEN", writer)
        for forbidden in (
            "getRequestURI",
            "getMethod",
            "AuthenticationServiceException",
            "getMessage",
            "/api/v1/",
        ):
            self.assertNotIn(forbidden, entry_point)
            self.assertNotIn(forbidden, writer)

    def test_generic_auth_problem은_global에만_존재하고_legacy는_non_API_fallback에만_남는다(self):
        main_root = ROOT / "services/spring-api/src/main/java"
        standard = (
            main_root
            / "com/timingjeju/api/global/error/StandardProblemCode.java"
        ).read_text(encoding="utf-8")
        places = (
            main_root
            / "com/timingjeju/api/domain/places/exception/PlacesProblemDefinitions.java"
        ).read_text(encoding="utf-8")

        self.assertEqual(standard.count("AUTHENTICATION_REQUIRED"), 1)
        self.assertEqual(standard.count("INVALID_ACCESS_TOKEN"), 1)
        self.assertNotIn("AUTHENTICATION_REQUIRED", places)
        self.assertNotIn("INVALID_ACCESS_TOKEN", places)
        legacy_owners = []
        for path in main_root.rglob("*.java"):
            if "AUTH_TOKEN_INVALID" in path.read_text(encoding="utf-8"):
                legacy_owners.append(path.relative_to(main_root).as_posix())
        self.assertEqual(
            sorted(legacy_owners),
            [
                "com/timingjeju/api/global/error/AuthenticationProblemWriter.java",
                "com/timingjeju/api/global/error/StandardProblemCode.java",
            ],
        )

    def test_API_entry_point_scope는_단일_prefix_matcher이고_endpoint_list가_아니다(self):
        security = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/global/config/SecurityConfig.java"
        ).read_text(encoding="utf-8")
        self.assertEqual(security.count('PathPatternRequestMatcher.pathPattern("/api/v1/**")'), 1)
        self.assertIn("DelegatingAuthenticationEntryPoint.builder()", security)
        self.assertIn('writeLegacy(request, response)', security)

    def test_current_docs는_legacy_code를_호환_계약으로_남기지_않는다(self):
        for relative in ("docs/AUTHENTICATION.md", "docs/FRONTEND_API_SPEC.md"):
            text = (ROOT / relative).read_text(encoding="utf-8")
            self.assertNotIn("AUTH_TOKEN_INVALID", text, relative)
            self.assertIn("AUTHENTICATION_REQUIRED", text, relative)
            self.assertIn("INVALID_ACCESS_TOKEN", text, relative)


if __name__ == "__main__":
    unittest.main()
