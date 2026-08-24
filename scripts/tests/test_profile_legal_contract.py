from __future__ import annotations

import json
import copy
import importlib.util
import subprocess
import tempfile
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/contracts/domains/profile-legal/contract.json"
FIXTURES = ROOT / "fixtures/contracts/profile-legal"
VALIDATOR = ROOT / "scripts/validate_profile_legal_contract.py"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"


class ProfileLegalContractTest(unittest.TestCase):
    maxDiff = None

    def _contract(self) -> dict:
        return json.loads(CONTRACT.read_text(encoding="utf-8"))

    def test_contract_identity_and_core5_extension_are_exact(self) -> None:
        contract = self._contract()

        self.assertEqual("timing-jeju-profile-legal-contract/v1", contract["schemaVersion"])
        self.assertEqual("1.0.0", contract["contractVersion"])
        self.assertEqual("timing-jeju-rest-contract/v1", contract["inherits"])
        self.assertEqual(82, contract["ownerIssue"])
        self.assertEqual(
            [
                ("GET", "/api/v1/me", "core"),
                ("PATCH", "/api/v1/me", "core"),
                ("DELETE", "/api/v1/me", "core"),
                ("GET", "/api/v1/legal-documents", "core"),
                ("PUT", "/api/v1/me/consents", "core"),
                (
                    "GET",
                    "/api/v1/account-deletion-requests/{deletionRequestId}",
                    "extension",
                ),
            ],
            [
                (endpoint["method"], endpoint["path"], endpoint["catalogKind"])
                for endpoint in contract["endpoints"]
            ],
        )

    def test_auth_owner_and_implementation_owners_are_closed(self) -> None:
        contract = self._contract()
        endpoints = {(item["method"], item["path"]): item for item in contract["endpoints"]}

        for identity, endpoint in endpoints.items():
            expected_mode = "optional" if identity == ("GET", "/api/v1/legal-documents") else "required"
            self.assertEqual(expected_mode, endpoint["auth"]["mode"])
            self.assertEqual(401, endpoint["auth"]["invalidToken"])
        self.assertEqual(61, endpoints[("DELETE", "/api/v1/me")]["implementationIssue"])
        self.assertEqual(
            61,
            endpoints[("GET", "/api/v1/account-deletion-requests/{deletionRequestId}")][
                "implementationIssue"
            ],
        )
        self.assertEqual(106, contract["deletionPolicy"]["workerIssue"])
        self.assertEqual("canonical Supabase JWT sub", contract["securityPolicy"]["principal"])

    def test_patch_omitted_null_and_object_key_semantics_are_exact(self) -> None:
        policy = self._contract()["profilePatchPolicy"]

        self.assertEqual("preserve", policy["nickname"]["omitted"])
        self.assertEqual("reject", policy["nickname"]["null"])
        self.assertEqual("preserve", policy["locale"]["omitted"])
        self.assertEqual("reject", policy["locale"]["null"])
        self.assertEqual("preserve", policy["profileImageObjectKey"]["omitted"])
        self.assertEqual("clear", policy["profileImageObjectKey"]["null"])
        self.assertEqual("private object key; never a provider URL", policy["profileImageObjectKey"]["meaning"])
        self.assertEqual("at least one mutable field", policy["requestConstraint"])

    def test_delete_is_202_replay_exact_and_status_token_is_opaque(self) -> None:
        policy = self._contract()["deletionPolicy"]

        self.assertEqual("DELETE_MY_ACCOUNT", policy["confirmation"])
        self.assertEqual(202, policy["acceptedStatus"])
        self.assertEqual("Idempotency-Key", policy["idempotencyHeader"])
        self.assertEqual("same user + key + canonical body", policy["replayScope"])
        self.assertEqual("return original deletionRequestId and exact original statusToken", policy["sameReplay"])
        self.assertEqual(256, policy["statusToken"]["entropyBits"])
        self.assertEqual("X-Deletion-Status-Token", policy["statusToken"]["header"])
        self.assertEqual("status verification until expiresAt; replay only before replayCutoff", policy["statusToken"]["use"])
        self.assertEqual(410, policy["statusToken"]["expiredStatus"])
        self.assertEqual("never plaintext", policy["statusToken"]["persistence"])

    def test_deletion_status_states_and_presence_are_complete(self) -> None:
        policy = self._contract()["deletionStatusPolicy"]

        self.assertEqual(
            ["queued", "running", "succeeded", "failed", "cancelled"],
            policy["states"],
        )
        self.assertEqual(
            ["deletionRequestId", "status", "currentStep", "nextRetryAt", "completedAt"],
            policy["responseFields"],
        )
        self.assertEqual(
            {
                "queued": {"currentStep": None, "nextRetryAt": "nullable", "completedAt": None},
                "running": {"currentStep": "required", "nextRetryAt": "nullable", "completedAt": None},
                "succeeded": {"currentStep": None, "nextRetryAt": None, "completedAt": "required"},
                "failed": {"currentStep": None, "nextRetryAt": "nullable", "completedAt": "required"},
                "cancelled": {"currentStep": None, "nextRetryAt": None, "completedAt": "required"},
            },
            policy["statePresence"],
        )
        self.assertEqual(["email", "nickname", "providerError", "authSubject"], policy["forbiddenFields"])

    def test_legal_document_selection_and_consent_withdrawal_are_exact(self) -> None:
        contract = self._contract()
        legal = contract["legalDocumentPolicy"]
        consent = contract["consentPolicy"]

        self.assertEqual("requested locale then ko-KR fallback", legal["localeSelection"])
        self.assertEqual("greatest effectiveAt <= evaluatedAt; version tie-break", legal["activeVersionSelection"])
        self.assertEqual("single captured server instant", legal["evaluatedAt"])
        self.assertEqual(["terms", "privacy", "location"], legal["types"])
        self.assertEqual("reject false with 422 REQUIRED_CONSENT_WITHDRAWAL_NOT_ALLOWED", consent["requiredWithdrawal"])
        self.assertEqual("replace specified document decisions atomically", consent["updateSemantics"])
        self.assertEqual("reject duplicate documentId", consent["duplicates"])

    def test_error_condition_matrix_is_exact_and_problem_details_are_closed(self) -> None:
        contract = self._contract()
        conditions = contract["errorConditions"]
        required_problem = {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"}

        expected_statuses = {400, 401, 403, 404, 409, 410, 422, 428, 429, 503}
        self.assertEqual(expected_statuses, {item["status"] for item in conditions})
        for condition in conditions:
            self.assertEqual(required_problem, set(condition["example"]))
            self.assertEqual(condition["status"], condition["example"]["status"])
            self.assertEqual(condition["code"], condition["example"]["code"])
            self.assertRegex(condition["example"]["title"], "[가-힣]")
            self.assertRegex(condition["example"]["detail"], "[가-힣]")

    def test_db_lineage_retention_and_secret_boundaries_are_explicit(self) -> None:
        storage = self._contract()["storagePolicy"]

        self.assertEqual(
            ["user_profiles", "social_accounts", "user_consents", "legal_documents", "account_deletion_requests"],
            storage["tables"],
        )
        self.assertEqual("nullable ON DELETE SET NULL", storage["accountDeletionUserProfileId"])
        self.assertEqual("hash plus encrypted ciphertext, key version and expiry", storage["statusToken"])
        self.assertEqual("preserve through token expiry and late retries; remove only after Auth success or safe terminalization", storage["workerAuthSubject"])
        self.assertEqual("remove status-token ciphertext/keyVersion at replayCutoff; preserve verifier hash and worker auth subject", storage["tokenCiphertextRetention"])
        self.assertEqual("retain irreversible hash until verifierCutoff for 410, then delete at equality", storage["tokenVerifierRetention"])
        self.assertEqual("no schema change; Issues #61 and #106 own discovered gaps", storage["migrationScope"])

    def test_external_readiness_is_truthful_and_implementation_not_ready(self) -> None:
        contract = self._contract()

        self.assertEqual(
            {"notion": "not-linked", "figma": "not-linked"},
            contract["externalTraceability"],
        )
        self.assertEqual("not-ready", contract["readiness"]["metadata"]["status"])
        self.assertIsNone(contract["readiness"]["metadata"]["evidence"])
        self.assertEqual("not-ready", contract["readiness"]["example"]["status"])
        self.assertIsNone(contract["readiness"]["example"]["evidence"])
        self.assertEqual("not-ready", contract["readiness"]["implementation"]["status"])
        self.assertEqual([61, 106], contract["readiness"]["implementation"]["blockedBy"])

    def test_fixtures_catalog_validator_and_quality_wiring_exist(self) -> None:
        contract = self._contract()
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        expected = {(item["method"], item["path"]) for item in contract["endpoints"]}
        projected = {
            (item["method"], item["path"])
            for item in catalog["endpoints"]
            if (item["method"], item["path"]) in expected
        }

        self.assertEqual(expected, projected)
        for name in ("request.json", "success.json", "problem.json"):
            self.assertTrue((FIXTURES / name).is_file(), name)
        self.assertTrue(VALIDATOR.is_file())
        result = subprocess.run(
            ["python3", str(VALIDATOR)],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("프로필·법정 문서 계약 검사 성공", result.stdout)

    def test_all_endpoint_schema_references_are_closed_and_resolved(self) -> None:
        contract = self._contract()
        schemas = contract["schemas"]
        referenced = set()
        for endpoint in contract["endpoints"]:
            referenced.update(value for value in endpoint["schemas"].values() if value != "none")
            referenced.add(endpoint["successSchema"])

        self.assertEqual(
            {
                "CommonHeaders",
                "OptionalAuthorizationHeaders",
                "ProfileResponse",
                "ProfilePatchRequest",
                "DeleteAccountHeaders",
                "DeleteAccountRequest",
                "DeleteAcceptedResponse",
                "LegalDocumentsQuery",
                "LegalDocumentsResponse",
                "ConsentRequest",
                "ConsentResponse",
                "DeletionRequestPath",
                "DeletionStatusHeaders",
                "DeletionStatusResponse",
            },
            referenced,
        )
        self.assertTrue(referenced <= set(schemas))
        for name, schema in schemas.items():
            with self.subTest(schema=name):
                if schema.get("type") == "object":
                    self.assertIs(False, schema["additionalProperties"])
                    self.assertTrue(set(schema.get("required", [])) <= set(schema["properties"]))

    def test_fixtures_cover_six_endpoints_and_all_five_deletion_states(self) -> None:
        request = json.loads((FIXTURES / "request.json").read_text(encoding="utf-8"))
        success = json.loads((FIXTURES / "success.json").read_text(encoding="utf-8"))
        expected = {(method, path) for method, path, _ in [
            ("GET", "/api/v1/me", "core"),
            ("PATCH", "/api/v1/me", "core"),
            ("DELETE", "/api/v1/me", "core"),
            ("GET", "/api/v1/legal-documents", "core"),
            ("PUT", "/api/v1/me/consents", "core"),
            ("GET", "/api/v1/account-deletion-requests/{deletionRequestId}", "extension"),
        ]}

        self.assertEqual(expected, {(item["method"], item["contractPath"]) for item in request["examples"]})
        self.assertEqual(expected, {(item["method"], item["contractPath"]) for item in success["examples"]})
        state_examples = success["deletionStatusExamples"]
        self.assertEqual(
            ["queued", "running", "succeeded", "failed", "cancelled"],
            [item["body"]["status"] for item in state_examples],
        )
        for item in state_examples:
            self.assertEqual(
                {"deletionRequestId", "status", "currentStep", "nextRetryAt", "completedAt"},
                set(item["body"]),
            )

    def test_catalog_kind_is_exact_in_contract_and_catalog(self) -> None:
        contract = self._contract()
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        actual = {(item["method"], item["path"]): item for item in catalog["endpoints"]}

        self.assertEqual(["core"] * 5 + ["extension"], [item["catalogKind"] for item in contract["endpoints"]])
        for endpoint in contract["endpoints"]:
            self.assertEqual(endpoint["catalogKind"], actual[(endpoint["method"], endpoint["path"])]["catalogKind"])

    def test_delete_apply_idempotency_inherits_exact_common_contract(self) -> None:
        endpoint = next(item for item in self._contract()["endpoints"] if item["method"] == "DELETE")

        self.assertEqual("apply", endpoint["operation"])
        self.assertEqual(
            {
                "required": True,
                "header": "Idempotency-Key",
                "scope": "canonical JWT sub + DELETE /api/v1/me + key",
                "ttl": "until statusTokenExpiresAt",
                "replay": "same canonical body returns original 202 body including exact statusToken",
                "payloadConflict": "409 IDEMPOTENCY_PAYLOAD_CONFLICT",
                "concurrentRequest": "409 IDEMPOTENCY_REQUEST_IN_PROGRESS",
            },
            endpoint["idempotency"],
        )

    def test_legal_optional_jwt_and_deletion_status_token_only_auth_are_explicit(self) -> None:
        endpoints = {(item["method"], item["path"]): item for item in self._contract()["endpoints"]}
        legal = endpoints[("GET", "/api/v1/legal-documents")]
        status = endpoints[("GET", "/api/v1/account-deletion-requests/{deletionRequestId}")]

        self.assertEqual("OptionalAuthorizationHeaders", legal["schemas"]["headers"])
        self.assertNotIn("authAlternative", legal)
        self.assertEqual("bearer-jwt/v1", legal["auth"].get("scheme", "bearer-jwt/v1"))
        self.assertEqual("DeletionStatusHeaders", status["schemas"]["headers"])
        self.assertNotIn("authAlternative", status)
        self.assertEqual("deletion-status-token/v1", status["auth"]["scheme"])

    def test_versioned_auth_schemes_are_typed_and_catalog_aligned(self) -> None:
        contract = self._contract()
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))

        self.assertEqual(
            {
                "bearer-jwt/v1": {
                    "type": "http-bearer-jwt",
                    "header": "Authorization",
                    "principal": "canonical JWT sub",
                    "missing": "mode-dependent",
                    "invalid": "401 INVALID_ACCESS_TOKEN",
                },
                "deletion-status-token/v1": {
                    "type": "opaque-header-capability",
                    "header": "X-Deletion-Status-Token",
                    "principal": "deletion request capability",
                    "jwt": "ignored and not an ownership source",
                    "missing": "401 INVALID_DELETION_STATUS_TOKEN",
                    "invalid": "401 INVALID_DELETION_STATUS_TOKEN",
                },
            },
            catalog["commonRules"]["authorization"]["schemes"],
        )
        self.assertEqual(
            ["bearer-jwt/v1"] * 5 + ["deletion-status-token/v1"],
            [endpoint["auth"].get("scheme", "bearer-jwt/v1") for endpoint in contract["endpoints"]],
        )

    def test_recursive_schema_and_fixture_validation_rejects_mutations(self) -> None:
        spec = importlib.util.spec_from_file_location("profile_legal_validator", VALIDATOR)
        assert spec is not None and spec.loader is not None
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        contract = self._contract()

        mutations = []
        wrong_type = copy.deepcopy(contract)
        wrong_type["schemas"]["ProfileResponse"]["properties"]["onboardingCompleted"]["type"] = "string"
        mutations.append(wrong_type)
        loosened = copy.deepcopy(contract)
        loosened["schemas"]["DeleteAccountRequest"]["additionalProperties"] = True
        mutations.append(loosened)
        widened = copy.deepcopy(contract)
        widened["schemas"]["ProfilePatchRequest"]["properties"]["nickname"]["maxLength"] = 5000
        mutations.append(widened)
        missing_nested = copy.deepcopy(contract)
        del missing_nested["schemas"]["LegalDocumentsResponse"]["properties"]["items"]["items"]["properties"]["effectiveAt"]
        mutations.append(missing_nested)

        for mutation in mutations:
            with self.subTest(mutation=mutations.index(mutation)):
                self.assertTrue(validator.validate_contract_value(mutation))

        invalid_fixture = json.loads((FIXTURES / "success.json").read_text(encoding="utf-8"))
        invalid_fixture["examples"][0]["body"]["onboardingCompleted"] = "true"
        self.assertTrue(validator.validate_fixture_value("success", invalid_fixture, contract))
        invalid_problem = json.loads((FIXTURES / "problem.json").read_text(encoding="utf-8"))
        invalid_problem["examples"][0]["body"]["status"] = "401"
        self.assertTrue(validator.validate_fixture_value("problem", invalid_problem, contract))

    def test_real_bearer_and_private_object_key_grammar_are_strict(self) -> None:
        schemas = self._contract()["schemas"]
        bearer = schemas["CommonHeaders"]["properties"]["Authorization"]["pattern"]
        object_key = schemas["ProfilePatchRequest"]["properties"]["profileImageObjectKey"]["pattern"]

        self.assertIsNotNone(re.fullmatch(bearer, "Bearer " + ("a" * 16)))
        self.assertIsNone(re.fullmatch(bearer, "Bearer <redacted>"))
        request = json.loads((FIXTURES / "request.json").read_text(encoding="utf-8"))
        committed_authorizations = [
            item["headers"]["Authorization"]
            for item in request["examples"]
            if "Authorization" in item.get("headers", {})
        ]
        self.assertEqual(["Bearer <fixture-access-token>"] * 4, committed_authorizations)
        for valid in ("profiles/user/avatar.webp", "a.png"):
            self.assertIsNotNone(re.fullmatch(object_key, valid))
        for invalid in ("https://cdn.example/a.png", "/absolute.png", "../secret", "a/../secret", "a\n.png"):
            self.assertIsNone(re.fullmatch(object_key, invalid))

    def test_endpoint_error_matrix_and_global_conditions_are_bidirectional(self) -> None:
        contract = self._contract()
        conditions = {item["code"]: item for item in contract["errorConditions"]}
        identities = {f'{item["method"]} {item["path"]}' for item in contract["endpoints"]}
        used = set()
        for endpoint in contract["endpoints"]:
            identity = f'{endpoint["method"]} {endpoint["path"]}'
            for status, codes in endpoint["errorMatrix"].items():
                self.assertIn(int(status), endpoint["responses"]["errors"])
                for code in codes:
                    self.assertEqual(int(status), conditions[code]["status"])
                    self.assertIn(identity, conditions[code]["endpoints"])
                    used.add(code)
        self.assertEqual(set(conditions), used)
        self.assertEqual(identities, {endpoint for item in conditions.values() for endpoint in item["endpoints"]})
        self.assertIn("INVALID_ACCESS_TOKEN", conditions)
        self.assertIn("INVALID_DELETION_STATUS_TOKEN", conditions)

    def test_error_matrix_code_status_and_endpoint_mutations_are_rejected(self) -> None:
        spec = importlib.util.spec_from_file_location("profile_legal_validator_errors", VALIDATOR)
        assert spec is not None and spec.loader is not None
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        contract = self._contract()

        status_mutation = copy.deepcopy(contract)
        condition = next(item for item in status_mutation["errorConditions"] if item["code"] == "INVALID_ACCESS_TOKEN")
        condition["status"] = 403
        code_mutation = copy.deepcopy(contract)
        endpoint = next(item for item in code_mutation["endpoints"] if item["path"] == "/api/v1/legal-documents")
        endpoint["errorMatrix"]["401"] = ["INVALID_DELETION_STATUS_TOKEN"]
        endpoint_mutation = copy.deepcopy(contract)
        condition = next(item for item in endpoint_mutation["errorConditions"] if item["code"] == "INVALID_ACCESS_TOKEN")
        condition["endpoints"] = ["GET /api/v1/me"]

        for mutation in (status_mutation, code_mutation, endpoint_mutation):
            self.assertTrue(validator.validate_contract_value(mutation))

    def test_deletion_status_problem_fixtures_match_canonical_examples_exactly(self) -> None:
        spec = importlib.util.spec_from_file_location("profile_legal_validator_problem_examples", VALIDATOR)
        assert spec is not None and spec.loader is not None
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        contract = self._contract()
        problem = json.loads((FIXTURES / "problem.json").read_text(encoding="utf-8"))
        conditions = {item["code"]: item for item in contract["errorConditions"]}
        target_codes = {
            "INVALID_DELETION_STATUS_TOKEN",
            "DELETION_STATUS_FORBIDDEN",
            "PROFILE_RESOURCE_NOT_FOUND",
        }

        for fixture in problem["examples"]:
            code = fixture["body"]["code"]
            if code not in target_codes:
                continue
            with self.subTest(code=code):
                canonical = conditions[code]["example"]
                self.assertEqual(canonical, fixture["body"])
                self.assertEqual("0123456789abcdef0123456789abcdef", canonical["traceId"])
                self.assertEqual(
                    f"urn:timing-jeju:problem:{canonical['traceId']}",
                    canonical["instance"],
                )

        canonical_fixture = copy.deepcopy(problem)
        for fixture in canonical_fixture["examples"]:
            code = fixture["body"]["code"]
            if code in target_codes:
                fixture["body"] = copy.deepcopy(conditions[code]["example"])
        target_index = next(
            index
            for index, fixture in enumerate(canonical_fixture["examples"])
            if fixture["body"]["code"] == "INVALID_DELETION_STATUS_TOKEN"
        )
        for field, drifted in (
            ("type", "https://api.timing-jeju.com/problems/arbitrary"),
            ("title", "임의 오류"),
            ("detail", "임의 상세입니다."),
        ):
            mutation = copy.deepcopy(canonical_fixture)
            mutation["examples"][target_index]["body"][field] = drifted
            with self.subTest(field=field):
                errors = validator.validate_fixture_value("problem", mutation, contract)
                self.assertTrue(any("canonical example" in error for error in errors), errors)

    def test_deletion_state_discriminator_rejects_invalid_presence(self) -> None:
        spec = importlib.util.spec_from_file_location("profile_legal_validator_states", VALIDATOR)
        assert spec is not None and spec.loader is not None
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        success = json.loads((FIXTURES / "success.json").read_text(encoding="utf-8"))

        for item in success["deletionStatusExamples"]:
            self.assertEqual([], validator.validate_deletion_status(item["body"]))
        invalid = copy.deepcopy(success["deletionStatusExamples"])
        invalid[0]["body"]["completedAt"] = "2026-08-24T10:00:00+09:00"
        invalid[1]["body"]["currentStep"] = None
        invalid[2]["body"]["completedAt"] = None
        invalid[3]["body"]["currentStep"] = "provider-message"
        invalid[4]["body"]["nextRetryAt"] = "2026-08-24T10:00:00+09:00"
        for item in invalid:
            self.assertTrue(validator.validate_deletion_status(item["body"]))

    def test_main_deletion_status_success_uses_recursive_schema_and_discriminator(self) -> None:
        spec = importlib.util.spec_from_file_location("profile_legal_validator_main_status", VALIDATOR)
        assert spec is not None and spec.loader is not None
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        contract = self._contract()
        success = json.loads((FIXTURES / "success.json").read_text(encoding="utf-8"))
        main = next(item for item in success["examples"] if item["contractPath"].startswith("/api/v1/account-deletion-requests/"))
        main["body"]["currentStep"] = None

        self.assertTrue(validator.validate_fixture_value("success", success, contract))

    def test_token_lifecycle_retention_rotation_and_failure_boundary_are_exact(self) -> None:
        lifecycle = self._contract()["deletionPolicy"]["tokenLifecycle"]

        self.assertEqual(86400, lifecycle["ttlSeconds"])
        self.assertEqual("nonterminal=statusTokenExpiresAt; terminal=min(statusTokenExpiresAt, terminalAt + 24h)", lifecycle["replayCutoff"])
        self.assertEqual("replay allowed iff now < replayCutoff; equality is not replayable", lifecycle["replayGuarantee"])
        self.assertEqual("delete status-token ciphertext and keyVersion; preserve irreversible verifier hash and encryptedAuthSubject", lifecycle["replayCutoffAction"])
        self.assertEqual("statusTokenExpiresAt + 24h", lifecycle["verifierCutoff"])
        self.assertEqual("delete irreversible verifier hash and retained status; subsequent token is invalid 401", lifecycle["verifierCutoffAction"])
        self.assertEqual("expiresAt <= now < verifierCutoff returns 410; equality at verifierCutoff deletes hash and is invalid 401", lifecycle["expiryClassification"])
        self.assertEqual(
            [
                {"case": "nonterminal-before-expiry", "now": "statusTokenExpiresAt - 1 microsecond", "replay": True, "status": 200},
                {"case": "nonterminal-expiry-equality", "now": "statusTokenExpiresAt", "replay": False, "status": 410},
                {"case": "terminal-replay-cutoff-equality", "now": "min(statusTokenExpiresAt, terminalAt + 24h)", "replay": False, "verifierHash": "preserved until verifierCutoff"},
                {"case": "verifier-cutoff-equality", "now": "statusTokenExpiresAt + 24h", "replay": False, "status": 401},
            ],
            lifecycle["boundaryCases"],
        )
        self.assertEqual("decrypt current keyVersion; re-encrypt with active key on successful replay", lifecycle["keyRotation"])
        self.assertEqual("fail closed 503 without new token or raw crypto/provider cause", lifecycle["cryptoFailure"])

    def test_worker_auth_subject_lifecycle_is_separate_from_status_token_cleanup(self) -> None:
        policy = self._contract()["deletionPolicy"]["workerAuthSubjectLifecycle"]

        self.assertEqual("never deleted by status token replayCutoff or verifierCutoff", policy["tokenCleanupIndependence"])
        self.assertEqual("preserve", policy["nonterminalTokenExpiry"])
        self.assertEqual("preserve for late worker Auth retry", policy["lateWorker"])
        self.assertEqual(
            ["Auth deletion succeeded", "safe terminalization guarantees no future Auth retry"],
            policy["deleteOnlyWhen"],
        )
        self.assertEqual(
            "remove encryptedAuthSubject in the same committed worker transition",
            policy["deleteAction"],
        )

    def test_deletion_status_capability_precedence_and_concealment_are_exact(self) -> None:
        contract = self._contract()
        policy = contract["deletionStatusPolicy"]["capabilityAuthorization"]

        self.assertEqual("verifier hash lookup first; constant-time compare; dummy compare on no hash row", policy["verification"])
        self.assertEqual(
            [
                {"case": "missing-or-malformed-token", "status": 401, "code": "INVALID_DELETION_STATUS_TOKEN", "lookup": "no identifier existence result"},
                {"case": "unknown-token-hash", "status": 401, "code": "INVALID_DELETION_STATUS_TOKEN", "lookup": "dummy constant-time verification"},
                {"case": "valid-token-mismatched-or-missing-id", "status": 403, "code": "DELETION_STATUS_FORBIDDEN", "lookup": "do not reveal identifier existence"},
                {"case": "valid-token-matching-id-status-missing", "status": 404, "code": "PROFILE_RESOURCE_NOT_FOUND", "lookup": "capability already proves identifier knowledge"},
            ],
            policy["precedence"],
        )
        self.assertEqual("same observable result for any unproven deletionRequestId", policy["existenceConcealment"])
        token_schema = contract["schemas"]["DeletionStatusHeaders"]["properties"]["X-Deletion-Status-Token"]
        self.assertEqual(
            {"minLength": 43, "maxLength": 43, "pattern": "^[A-Za-z0-9_-]{43}$"},
            {key: token_schema[key] for key in ("minLength", "maxLength", "pattern")},
        )
        problem = json.loads((FIXTURES / "problem.json").read_text(encoding="utf-8"))
        status_examples = {
            (item["body"]["status"], item["body"]["code"])
            for item in problem["examples"]
            if item["endpoint"].startswith("GET /api/v1/account-deletion-requests/")
        }
        self.assertTrue(
            {(401, "INVALID_DELETION_STATUS_TOKEN"), (403, "DELETION_STATUS_FORBIDDEN"), (404, "PROFILE_RESOURCE_NOT_FOUND")}
            <= status_examples
        )

        spec = importlib.util.spec_from_file_location("profile_legal_validator_capability", VALIDATOR)
        assert spec is not None and spec.loader is not None
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        mutation = copy.deepcopy(contract)
        mutation["deletionStatusPolicy"]["capabilityAuthorization"]["precedence"][2]["status"] = 404
        self.assertTrue(any("capability" in error for error in validator.validate_contract_value(mutation)))

    def test_recursive_schema_keyword_engine_rejects_unknown_types_ranges_and_duplicates(self) -> None:
        spec = importlib.util.spec_from_file_location("profile_legal_validator_keywords", VALIDATOR)
        assert spec is not None and spec.loader is not None
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        contract = self._contract()

        mutations = []
        unknown = copy.deepcopy(contract)
        unknown["schemas"]["ProfileResponse"]["properties"]["nickname"]["maxLenght"] = 50
        mutations.append(unknown)
        nullable_type = copy.deepcopy(contract)
        nullable_type["schemas"]["ProfileResponse"]["properties"]["nickname"]["nullable"] = "false"
        mutations.append(nullable_type)
        reversed_range = copy.deepcopy(contract)
        reversed_range["schemas"]["ProfileResponse"]["properties"]["nickname"]["minLength"] = 51
        mutations.append(reversed_range)
        duplicate_enum = copy.deepcopy(contract)
        duplicate_enum["schemas"]["ProfileResponse"]["properties"]["locale"]["enum"].append("ko-KR")
        mutations.append(duplicate_enum)
        unique_items_type = copy.deepcopy(contract)
        unique_items_type["schemas"]["ProfileResponse"]["properties"]["providers"]["uniqueItems"] = "true"
        mutations.append(unique_items_type)

        for mutation in mutations:
            errors = validator.validate_contract_value(mutation)
            self.assertTrue(errors)
            self.assertTrue(
                any("schema keyword" in error or "schema constraint" in error for error in errors),
                errors,
            )

        enum_errors = []
        validator._validate_schema_value(
            2,
            {"type": "integer", "nullable": False, "enum": [1]},
            "fixture.integer",
            enum_errors,
        )
        self.assertTrue(any("enum" in error for error in enum_errors), enum_errors)

        raw = CONTRACT.read_text(encoding="utf-8").replace(
            '"maxLength": 254', '"maxLength": 254, "maxLength": 255', 1
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            duplicate_path = Path(temp_dir) / "contract.json"
            duplicate_path.write_text(raw, encoding="utf-8")
            result = subprocess.run(
                ["python3", str(VALIDATOR), "--contract", str(duplicate_path)],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
        self.assertEqual(1, result.returncode)
        self.assertRegex(result.stderr, "읽거나 검증할 수 없습니다|중복")
        self.assertNotIn("Traceback", result.stderr)

    def test_fixture_path_status_uniqueness_and_recursive_boundaries_are_rejected(self) -> None:
        spec = importlib.util.spec_from_file_location("profile_legal_validator_fixture_boundaries", VALIDATOR)
        assert spec is not None and spec.loader is not None
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        contract = self._contract()
        request = json.loads((FIXTURES / "request.json").read_text(encoding="utf-8"))
        success = json.loads((FIXTURES / "success.json").read_text(encoding="utf-8"))
        problem = json.loads((FIXTURES / "problem.json").read_text(encoding="utf-8"))

        mutations = []
        duplicate_endpoint = copy.deepcopy(request)
        duplicate_endpoint["examples"].append(copy.deepcopy(duplicate_endpoint["examples"][0]))
        mutations.append(("request", duplicate_endpoint))
        wrong_path = copy.deepcopy(request)
        wrong_path["examples"][-1]["path"] = "/api/v1/account-deletion-requests/not-a-ulid"
        mutations.append(("request", wrong_path))
        malformed_status_token = copy.deepcopy(request)
        malformed_status_token["examples"][-1]["headers"]["X-Deletion-Status-Token"] = "***"
        mutations.append(("request", malformed_status_token))
        extra_key = copy.deepcopy(request)
        extra_key["examples"][0]["unexpected"] = True
        mutations.append(("request", extra_key))
        duplicate_consent = copy.deepcopy(request)
        consent = duplicate_consent["examples"][4]["body"]["consents"]
        consent.append(copy.deepcopy(consent[0]))
        mutations.append(("request", duplicate_consent))
        invalid_status = copy.deepcopy(success)
        invalid_status["examples"][0]["status"] = 201
        mutations.append(("success", invalid_status))
        invalid_datetime = copy.deepcopy(success)
        invalid_datetime["examples"][0]["body"]["updatedAt"] = "2026-08-24"
        mutations.append(("success", invalid_datetime))
        duplicate_state = copy.deepcopy(success)
        duplicate_state["deletionStatusExamples"][-1] = copy.deepcopy(duplicate_state["deletionStatusExamples"][0])
        mutations.append(("success", duplicate_state))
        duplicate_provider = copy.deepcopy(success)
        duplicate_provider["examples"][0]["body"]["providers"] = ["google", "google"]
        mutations.append(("success", duplicate_provider))
        unstable_provider = copy.deepcopy(success)
        unstable_provider["examples"][0]["body"]["providers"] = ["custom:naver", "google"]
        mutations.append(("success", unstable_provider))
        duplicate_problem = copy.deepcopy(problem)
        duplicate_problem["examples"].append(copy.deepcopy(duplicate_problem["examples"][0]))
        mutations.append(("problem", duplicate_problem))
        success_status_problem = copy.deepcopy(problem)
        success_status_problem["examples"][0]["body"]["status"] = 200
        mutations.append(("problem", success_status_problem))

        for index, (kind, mutation) in enumerate(mutations):
            with self.subTest(kind=kind, index=index):
                self.assertTrue(validator.validate_fixture_value(kind, mutation, contract))

    def test_profile_provider_projection_is_closed_normalized_deduplicated_and_stable(self) -> None:
        contract = self._contract()
        providers = contract["profileProviderPolicy"]
        provider_array = contract["schemas"]["ProfileResponse"]["properties"]["providers"]
        schema = provider_array["items"]
        success = json.loads((FIXTURES / "success.json").read_text(encoding="utf-8"))

        self.assertEqual(0, provider_array["minItems"])
        self.assertIs(True, provider_array["uniqueItems"])
        self.assertEqual(["google", "kakao", "custom:naver"], providers["allowed"])
        self.assertEqual("trim then Unicode-independent ASCII lowercase", providers["normalization"])
        self.assertEqual("deduplicate after normalization", providers["deduplication"])
        self.assertEqual(["google", "kakao", "custom:naver"], providers["stableOrder"])
        self.assertEqual("exclude; email-only identity projects []", providers["emailIdentity"])
        self.assertEqual(["google", "kakao", "custom:naver"], schema["enum"])
        self.assertEqual([], success["examples"][0]["body"]["providers"])
        self.assertEqual(["kakao"], success["examples"][1]["body"]["providers"])
        self.assertNotIn("email", {provider for item in success["examples"][:2] for provider in item["body"]["providers"]})

    def test_provider_projection_fixtures_cover_email_only_single_and_normalized_multiple(self) -> None:
        success = json.loads((FIXTURES / "success.json").read_text(encoding="utf-8"))

        self.assertEqual(
            [
                {
                    "case": "email-only",
                    "sourceProviders": ["email"],
                    "providers": [],
                },
                {
                    "case": "single-oauth",
                    "sourceProviders": ["email", "kakao"],
                    "providers": ["kakao"],
                },
                {
                    "case": "normalized-deduplicated-multiple",
                    "sourceProviders": [" custom:naver ", "GOOGLE", "google", "email"],
                    "providers": ["google", "custom:naver"],
                },
            ],
            success["providerProjectionExamples"],
        )

    def test_provider_projection_fixture_mutations_reject_unknown_duplicate_and_unsorted_output(self) -> None:
        spec = importlib.util.spec_from_file_location("profile_provider_projection_validator", VALIDATOR)
        assert spec is not None and spec.loader is not None
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        contract = self._contract()
        success = json.loads((FIXTURES / "success.json").read_text(encoding="utf-8"))

        mutations = []
        unknown = copy.deepcopy(success)
        unknown["providerProjectionExamples"][1]["providers"] = ["github"]
        mutations.append(unknown)
        unknown_source = copy.deepcopy(success)
        unknown_source["providerProjectionExamples"][1]["sourceProviders"] = ["email", "github"]
        mutations.append(unknown_source)
        duplicate = copy.deepcopy(success)
        duplicate["providerProjectionExamples"][2]["providers"] = ["google", "google", "custom:naver"]
        mutations.append(duplicate)
        unsorted = copy.deepcopy(success)
        unsorted["providerProjectionExamples"][2]["providers"] = ["custom:naver", "google"]
        mutations.append(unsorted)

        for mutation in mutations:
            errors = validator.validate_fixture_value("success", mutation, contract)
            self.assertTrue(any("provider projection" in error for error in errors), errors)

        nonempty_only = copy.deepcopy(contract)
        nonempty_only["schemas"]["ProfileResponse"]["properties"]["providers"]["minItems"] = 1
        errors = validator.validate_contract_value(nonempty_only)
        self.assertTrue(any("email-only" in error for error in errors), errors)

    def test_legal_selection_partition_fallback_order_and_equality_are_deterministic(self) -> None:
        selection = self._contract()["legalDocumentPolicy"]

        self.assertEqual(["type", "requestedLocale"], selection["partition"])
        self.assertEqual("requested locale if any eligible row exists for that type, otherwise ko-KR", selection["fallback"])
        self.assertEqual("effectiveAt <= evaluatedAt", selection["eligibility"])
        self.assertEqual(["effectiveAt DESC", "semanticVersion DESC", "documentId ASC"], selection["order"])
        self.assertEqual("eligible and wins over older versions", selection["effectiveAtEquality"])


if __name__ == "__main__":
    unittest.main()
