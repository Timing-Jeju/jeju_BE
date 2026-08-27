#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "docs/contracts/domains/push-notifications/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
TOKEN_PATTERN = "^[!-~]{1,4096}$"
KEYS = (
    ("PUT", "/api/v1/me/push-devices/{deviceId}"),
    ("DELETE", "/api/v1/me/push-devices/{deviceId}"),
    ("GET", "/api/v1/me/notification-preferences"),
    ("PATCH", "/api/v1/me/notification-preferences"),
)
MATRIX = {
    KEYS[0]: ("PushDevicePath", "PushDeviceRegistrationRequest", 200, "PushDeviceResponse", [(400, "INVALID_PUSH_NOTIFICATION_REQUEST"), (401, "AUTHENTICATION_REQUIRED"), (401, "INVALID_ACCESS_TOKEN"), (403, "AUTH_ACCESS_DENIED"), (500, "INTERNAL_SERVER_ERROR"), (503, "PUSH_NOTIFICATION_DATA_UNAVAILABLE")]),
    KEYS[1]: ("PushDevicePath", "none", 204, "none", [(400, "INVALID_PUSH_NOTIFICATION_REQUEST"), (401, "AUTHENTICATION_REQUIRED"), (401, "INVALID_ACCESS_TOKEN"), (403, "AUTH_ACCESS_DENIED"), (500, "INTERNAL_SERVER_ERROR"), (503, "PUSH_NOTIFICATION_DATA_UNAVAILABLE")]),
    KEYS[2]: ("none", "none", 200, "NotificationPreferenceResponse", [(401, "AUTHENTICATION_REQUIRED"), (401, "INVALID_ACCESS_TOKEN"), (403, "AUTH_ACCESS_DENIED"), (500, "INTERNAL_SERVER_ERROR"), (503, "PUSH_NOTIFICATION_DATA_UNAVAILABLE")]),
    KEYS[3]: ("none", "NotificationPreferencePatchRequest", 200, "NotificationPreferenceResponse", [(400, "INVALID_PUSH_NOTIFICATION_REQUEST"), (401, "AUTHENTICATION_REQUIRED"), (401, "INVALID_ACCESS_TOKEN"), (403, "AUTH_ACCESS_DENIED"), (500, "INTERNAL_SERVER_ERROR"), (503, "PUSH_NOTIFICATION_DATA_UNAVAILABLE")]),
}
CONDITIONS = {
    KEYS[0]: ["invalid canonical UUID, closed body, field, or token boundary", "missing bearer JWT", "invalid bearer JWT", "authenticated principal lacks endpoint access", "unexpected server failure", "token protection or persistence unavailable"],
    KEYS[1]: ["invalid canonical UUID", "missing bearer JWT", "invalid bearer JWT", "authenticated principal lacks endpoint access", "unexpected server failure", "persistence unavailable"],
    KEYS[2]: ["missing bearer JWT", "invalid bearer JWT", "authenticated principal lacks endpoint access", "unexpected server failure", "persistence unavailable"],
    KEYS[3]: ["empty, null, unknown, or out-of-range body", "missing bearer JWT", "invalid bearer JWT", "authenticated principal lacks endpoint access", "unexpected server failure", "persistence unavailable"],
}


def validate_contract(contract: dict[str, Any], catalog: dict[str, Any], repo_root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    expected_top = {"contractId", "contractVersion", "issue", "inherits", "owner", "limits", "schemas", "endpointContracts", "catalogProjection", "runtimeDrift", "cryptoFailure", "legalSelection", "withdrawalLifecycle", "database", "externalTraceability", "localEvidence"}
    if set(contract) != expected_top:
        errors.append("contract top-level fields drift")
    if (contract.get("contractId"), contract.get("contractVersion"), contract.get("issue"), contract.get("inherits")) != (
        "push-notifications/v1", "1.0.0", 113, "timing-jeju-rest-contract/v1"
    ):
        errors.append("contract identity drift")
    if contract.get("owner") != {
        "apiPrincipal": "canonical JWT sub UUID",
        "requestOwnerFields": "forbidden",
        "databaseOwner": "(select auth.uid()) = user_id",
    }:
        errors.append("owner boundary drift")

    limits = contract.get("limits", {})
    if limits.get("deviceId") != {
        "type": "string", "format": "uuid", "length": 36, "pattern": UUID_PATTERN,
        "example": "11300000-0000-0000-0000-000000000101",
    }:
        errors.append("deviceId canonical UUID limit drift")
    token = limits.get("registrationToken", {})
    if {k: token.get(k) for k in ("type", "encoding", "pattern", "minBytes", "maxBytes")} != {
        "type": "string", "encoding": "printable ASCII encoded as UTF-8", "pattern": TOKEN_PATTERN,
        "minBytes": 1, "maxBytes": 4096,
    }:
        errors.append("registration token byte boundary drift")
    if token.get("envelope") != {
        "cipher": "AES-256-GCM", "versionBytes": 1, "ivBytes": 12, "tagBytes": 16,
        "encoding": "base64url without padding", "maxChars": 5500,
    }:
        errors.append("registration token envelope drift")

    schemas = contract.get("schemas", {})
    if set(schemas) != {"PushDeviceRegistrationRequest", "PushDeviceResponse", "NotificationPreferencePatchRequest", "NotificationPreferenceResponse"}:
        errors.append("closed schema catalog drift")
    for name, required in {
        "PushDeviceRegistrationRequest": {"platform", "registrationToken", "permissionStatus", "appVersion", "locale", "timeZone"},
        "PushDeviceResponse": {"deviceId", "platform", "permissionStatus", "active", "updatedAt"},
        "NotificationPreferenceResponse": {"nextDestinationDepartureEnabled", "safetyBufferMinutes", "updatedAt"},
    }.items():
        schema = schemas.get(name, {})
        if schema.get("type") != "object" or schema.get("additionalProperties") is not False or set(schema.get("required", [])) != required or set(schema.get("properties", {})) != required:
            errors.append(f"{name} typed closed schema drift")
    patch = schemas.get("NotificationPreferencePatchRequest", {})
    if patch.get("type") != "object" or patch.get("additionalProperties") is not False or patch.get("minProperties") != 1 or set(patch.get("properties", {})) != {"nextDestinationDepartureEnabled", "safetyBufferMinutes"}:
        errors.append("NotificationPreferencePatchRequest typed closed schema drift")
    request_properties = schemas.get("PushDeviceRegistrationRequest", {}).get("properties", {})
    expected_request_properties = {
        "platform": {"type": "string", "enum": ["IOS", "ANDROID"]},
        "registrationToken": {"$ref": "#/limits/registrationToken"},
        "permissionStatus": {"type": "string", "enum": ["GRANTED", "DENIED", "NOT_DETERMINED"]},
        "appVersion": {"type": "string", "minLength": 1, "maxLength": 50},
        "locale": {
            "type": "string", "minLength": 2, "maxLength": 35,
            "pattern": "^[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-[A-Z]{2}|-[0-9]{3})?(?:-[A-Za-z0-9]{5,8}|-[0-9][A-Za-z0-9]{3})*(?:-[0-9A-WYZa-wy-z](?:-[A-Za-z0-9]{2,8})+)*(?:-x(?:-[A-Za-z0-9]{1,8})+)?$",
            "example": "en-US-u-ca-gregory",
        },
        "timeZone": {"type": "string", "minLength": 1, "maxLength": 64},
    }
    if request_properties != expected_request_properties:
        errors.append("PushDeviceRegistrationRequest field schema drift")
    if schemas.get("PushDeviceResponse", {}).get("properties") != {
        "deviceId": {"$ref": "#/limits/deviceId"},
        "platform": {"type": "string", "enum": ["IOS", "ANDROID"]},
        "permissionStatus": {"type": "string", "enum": ["GRANTED", "DENIED", "NOT_DETERMINED"]},
        "active": {"type": "boolean"},
        "updatedAt": {"type": "string", "format": "date-time"},
    }:
        errors.append("PushDeviceResponse field schema drift")
    if patch.get("properties") != {
        "nextDestinationDepartureEnabled": {"type": "boolean"},
        "safetyBufferMinutes": {"$ref": "#/limits/safetyBufferMinutes"},
    }:
        errors.append("NotificationPreferencePatchRequest field schema drift")
    if schemas.get("NotificationPreferenceResponse", {}).get("properties") != {
        "nextDestinationDepartureEnabled": {"type": "boolean", "default": False},
        "safetyBufferMinutes": {"$ref": "#/limits/safetyBufferMinutes"},
        "updatedAt": {"type": ["string", "null"], "format": "date-time"},
    }:
        errors.append("NotificationPreferenceResponse field schema drift")

    endpoint_by_key = {(e.get("method"), e.get("path")): e for e in contract.get("endpointContracts", [])}
    if set(endpoint_by_key) != set(KEYS) or len(contract.get("endpointContracts", [])) != 4:
        errors.append("four endpoint contracts required")
    expected_projection = []
    for key, (path_schema, body_schema, success_status, success_schema, problems) in MATRIX.items():
        endpoint = endpoint_by_key.get(key, {})
        expected_success = {"status": success_status, "mediaType": "none" if success_status == 204 else "application/json", "schema": success_schema}
        actual_problems = [(p.get("status"), p.get("code")) for p in endpoint.get("problems", [])]
        if endpoint.get("auth") != "required bearer-jwt/v1" or endpoint.get("pathSchema") != path_schema or endpoint.get("bodySchema") != body_schema or endpoint.get("success") != expected_success or actual_problems != problems or [p.get("condition") for p in endpoint.get("problems", [])] != CONDITIONS[key] or any(p.get("mediaType") != "application/problem+json" for p in endpoint.get("problems", [])):
            errors.append(f"{key} response/problem matrix drift")
        expected_projection.append({
            "method": key[0], "path": key[1], "pathSchema": path_schema, "bodySchema": body_schema,
            "success": [success_status], "errors": list(dict.fromkeys(status for status, _ in problems)),
        })
    if contract.get("catalogProjection") != expected_projection:
        errors.append("local catalog projection drift")

    catalog_push = [e for e in catalog.get("endpoints", []) if (e.get("method"), e.get("path")) in KEYS]
    if len(catalog_push) != 4:
        errors.append("catalog must contain exactly four push notification endpoints")
    actual_catalog_projection = [{
        "method": e.get("method"), "path": e.get("path"), "pathSchema": e.get("schemas", {}).get("path"),
        "bodySchema": e.get("schemas", {}).get("body"), "success": e.get("responses", {}).get("success"),
        "errors": e.get("responses", {}).get("errors"),
    } for e in catalog_push]
    if actual_catalog_projection != expected_projection:
        errors.append("catalog projection does not exactly match domain contract")
    expected_db_owners = {
        KEYS[0]: "Spring service_role sole writer for public.push_devices.user_id; authenticated owner safe-column SELECT only; implementation #113",
        KEYS[1]: "Spring service_role sole writer for public.push_devices.user_id; authenticated owner safe-column SELECT only; implementation #113",
        KEYS[2]: "Spring service_role sole writer for public.notification_preferences.user_id; authenticated owner SELECT only; implementation #113",
        KEYS[3]: "Spring service_role sole writer for public.notification_preferences.user_id; authenticated owner SELECT only; implementation #113",
    }
    for endpoint in catalog_push:
        if endpoint.get("owner") != "canonical JWT sub" or "user_id" not in endpoint.get("dbOwner", "") or endpoint.get("auth") != {"mode": "required", "missingToken": 401, "invalidToken": 401}:
            errors.append("catalog owner/auth/dbOwner drift")
        if endpoint.get("dbOwner") != expected_db_owners.get(
            (endpoint.get("method"), endpoint.get("path"))
        ):
            errors.append("catalog server-writer/read-only client boundary drift")

    domain = [d for d in catalog.get("domainContracts", []) if d.get("issue") == 113]
    expected_domain = {"issue": 113, "domain": "push-notifications", "inherits": "timing-jeju-rest-contract/v1", "versions": {"local": "1.0.0", "notion": "not-linked", "figma": "not-linked"}, "readiness": {stage: {"status": "not-ready", "evidence": None} for stage in ("metadata", "example", "implementation")}}
    if domain != [expected_domain]:
        errors.append("issue 113 honest not-linked/not-ready domain row required")

    database = contract.get("database", {})
    if database != {
        "migrations": [
            "supabase/migrations/20260902000000_push_device_notification_preferences.sql",
            "supabase/migrations/20260902000001_push_notification_server_writer_boundary.sql",
        ],
        "tables": ["public.push_devices", "public.notification_preferences"], "ownerColumn": "user_id",
        "rlsPredicate": "(select auth.uid()) = user_id", "anonymousAccess": False,
        "authenticatedAccess": "owner safe-column SELECT only; no INSERT, UPDATE, DELETE grant or policy",
        "writerRole": "Spring server service_role only", "clientWritePolicyCount": 0,
        "securityDefiner": False, "activeTokenUniqueness": "unique token_fingerprint where invalidated_at is null",
        "ciphertextMaxChars": 5500,
    }:
        errors.append("database owner/RLS/token lineage drift")
    if contract.get("externalTraceability") != {"notion": "not-linked", "figma": "not-linked"}:
        errors.append("external traceability must remain honest")
    authorization = catalog.get("commonRules", {}).get("authorization", {})
    if authorization.get("missingTokenCode") != "AUTHENTICATION_REQUIRED" or authorization.get("invalidTokenCode") != "INVALID_ACCESS_TOKEN":
        errors.append("canonical common authorization codes drift")
    if contract.get("runtimeDrift") != {
        "canonicalMissingBearerCode": "AUTHENTICATION_REQUIRED",
        "canonicalInvalidBearerCode": "INVALID_ACCESS_TOKEN",
        "observedRequiredEndpointCode": "AUTH_TOKEN_INVALID",
        "owner": "global security follow-up outside Issue #113",
        "implementationReady": False,
    }:
        errors.append("required-endpoint runtime security drift must remain explicit and fail-closed")
    if contract.get("cryptoFailure") != {
        "boundary": "RegistrationTokenProtector runtime failure -> provider-neutral application exception",
        "status": 503,
        "code": "PUSH_NOTIFICATION_DATA_UNAVAILABLE",
        "storeCalls": 0,
        "rawCauseOrTokenExposure": False,
    }:
        errors.append("crypto failure mapping drift")
    if contract.get("legalSelection") != {
        "locale": "user_profiles.locale when any eligible location document exists; otherwise ko-KR",
        "eligibility": "required and effectiveAt <= evaluatedAt and not retired",
        "order": ["effectiveAt DESC", "semanticVersion DESC", "documentId ASC"],
        "consent": "winning document requires agreed=true, withdrawnAt=null, agreedAt<=evaluatedAt",
        "snapshotIsolation": "REPEATABLE_READ",
        "snapshotAnchor": "first database read in findEligible",
        "concurrentCommitVisibility": "next invocation",
    }:
        errors.append("legal selection drift")
    if contract.get("withdrawalLifecycle") != {
        "singleDeviceLogout": "DELETE /api/v1/me/push-devices/{deviceId}",
        "requestBoundary": "PushNotificationWithdrawalBoundary.onWithdrawalRequested",
        "requestOwner": "Issue #61/#106 invokes additive port in withdrawal intake transaction",
        "requestEffect": "all owner devices invalidated immediately; eligibility=0",
        "finalAuthDeletion": "auth.users delete cascades push_devices and notification_preferences",
        "crossOwnerEffect": "none",
    }:
        errors.append("member withdrawal lifecycle drift")
    for label, relative in contract.get("localEvidence", {}).items():
        path = repo_root / relative
        if not path.is_file():
            errors.append(f"local evidence missing: {label}")

    migration_paths = [repo_root / relative for relative in database.get("migrations", [])]
    if migration_paths and all(path.is_file() for path in migration_paths):
        migration = " ".join(
            re.sub(r"\s+", " ", path.read_text(encoding="utf-8").lower())
            for path in migration_paths
        )
        for fragment in (
            "char_length(token_ciphertext) between 1 and 5500",
            "char_length(locale) between 2 and 35",
            "(select auth.uid()) = user_id",
            "revoke all on public.push_devices from anon",
            "where invalidated_at is null",
            "drop policy if exists push_devices_owner_insert",
            "drop policy if exists push_devices_owner_update",
            "drop policy if exists notification_preferences_owner_insert",
            "drop policy if exists notification_preferences_owner_update",
            "revoke insert, update, delete on public.push_devices from authenticated",
            "revoke insert, update, delete on public.notification_preferences from authenticated",
        ):
            if fragment not in migration:
                errors.append(f"migration lineage missing: {fragment}")
        if "security definer" in migration:
            errors.append("SECURITY DEFINER is forbidden")
    else:
        errors.append("migration evidence missing")
    return errors


def main() -> None:
    errors = validate_contract(
        json.loads(CONTRACT.read_text(encoding="utf-8")),
        json.loads(CATALOG.read_text(encoding="utf-8")),
        ROOT,
    )
    if errors:
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
