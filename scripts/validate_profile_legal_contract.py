#!/usr/bin/env python3
"""Issue #82 프로필·법정 문서 계약을 fail-closed로 검사한다."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/profile-legal/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
FIXTURES = ROOT / "fixtures/contracts/profile-legal"
IDENTITIES = [
    ("GET", "/api/v1/me", "core"),
    ("PATCH", "/api/v1/me", "core"),
    ("DELETE", "/api/v1/me", "core"),
    ("GET", "/api/v1/legal-documents", "core"),
    ("PUT", "/api/v1/me/consents", "core"),
    ("GET", "/api/v1/account-deletion-requests/{deletionRequestId}", "extension"),
]
PROBLEM_FIELDS = {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"}
CANONICAL_PROBLEM_TRACE_ID = "0123456789abcdef0123456789abcdef"
CANONICAL_PROBLEM_INSTANCE = f"urn:timing-jeju:problem:{CANONICAL_PROBLEM_TRACE_ID}"
CATALOG_FIELDS = {
    "method", "path", "operation", "auth", "owner", "schemas", "presence", "responses",
    "dbOwner", "requestTimeCall", "dataLineage", "figma", "contractVersion", "idempotency", "pagination", "catalogKind",
}
FORBIDDEN_TEXT = re.compile(r"(?i)(eyJ[a-z0-9_-]{10,}|service[_-]?role|sk_live_|-----BEGIN|providerError\s*[:=])")


class DuplicateKey(ValueError):
    pass


def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKey(key)
        result[key] = value
    return result


def _load(path: Path) -> Any:
    return json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=_pairs,
        parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)),
    )


def catalog_projection(endpoint: dict[str, Any]) -> dict[str, Any]:
    return {key: endpoint[key] for key in CATALOG_FIELDS}


def _validate_contract(contract: Any, errors: list[str]) -> None:
    if not isinstance(contract, dict):
        errors.append("계약 root는 객체여야 합니다.")
        return
    required = {
        "schemaVersion", "contractVersion", "inherits", "ownerIssue", "implementationIssues", "schemas",
        "endpoints", "profilePatchPolicy", "deletionPolicy", "deletionStatusPolicy", "legalDocumentPolicy",
        "consentPolicy", "securityPolicy", "storagePolicy", "errorConditions", "externalTraceability", "readiness",
        "profileProviderPolicy",
    }
    if set(contract) != required:
        errors.append("계약 top-level exact 필드가 다릅니다.")
    if (
        contract.get("schemaVersion") != "timing-jeju-profile-legal-contract/v1"
        or contract.get("contractVersion") != "1.0.0"
        or contract.get("inherits") != "timing-jeju-rest-contract/v1"
        or contract.get("ownerIssue") != 82
        or contract.get("implementationIssues") != [18, 19, 61, 106]
    ):
        errors.append("계약 identity/version/owner가 다릅니다.")
    endpoints = contract.get("endpoints")
    if not isinstance(endpoints, list) or [
        (item.get("method"), item.get("path"), item.get("catalogKind"))
        for item in endpoints if isinstance(item, dict)
    ] != IDENTITIES:
        errors.append("core5 + deletion status extension endpoint 집합/순서가 다릅니다.")
    elif len({(item["method"], item["path"]) for item in endpoints}) != 6:
        errors.append("endpoint method/path가 중복됐습니다.")
    else:
        implementation_owners = [18, 18, 61, 19, 19, 61]
        for index, endpoint in enumerate(endpoints):
            if endpoint.get("contractVersion") != "1.0.0":
                errors.append("endpoint contractVersion drift가 있습니다.")
            if endpoint.get("implementationIssue") != implementation_owners[index]:
                errors.append("profile/legal/deletion API implementation owner가 endpoint별 계약과 다릅니다.")
            if set(catalog_projection(endpoint)) != CATALOG_FIELDS:
                errors.append("endpoint catalog projection 필드가 다릅니다.")
            expected_scheme = "deletion-status-token/v1" if index == 5 else "bearer-jwt/v1"
            if endpoint.get("auth", {}).get("scheme", "bearer-jwt/v1") != expected_scheme:
                errors.append("endpoint auth scheme이 credential contract와 다릅니다.")
    if contract.get("externalTraceability") != {"notion": "not-linked", "figma": "not-linked"}:
        errors.append("근거 없는 Notion/Figma readiness 승격을 허용하지 않습니다.")
    readiness = contract.get("readiness")
    if not isinstance(readiness, dict):
        errors.append("readiness가 필요합니다.")
    else:
        if readiness.get("metadata") != {"status": "not-ready", "evidence": None}:
            errors.append("metadata readiness는 외부 lineage 전 not-ready여야 합니다.")
        implementation = readiness.get("implementation")
        if implementation != {
            "status": "not-ready",
            "evidence": None,
            "implementedBy": [18, 19],
            "blockedBy": [61, 106],
        }:
            errors.append("implementation readiness는 #18/#19 완료와 #61/#106 잔여 범위를 분리해야 합니다.")
    states = contract.get("deletionStatusPolicy", {}).get("states")
    if states != ["queued", "running", "succeeded", "failed", "cancelled"]:
        errors.append("삭제 상태 five-state 계약이 다릅니다.")
    status_token = contract.get("deletionPolicy", {}).get("statusToken", {})
    if status_token.get("entropyBits") != 256 or status_token.get("persistence") != "never plaintext":
        errors.append("삭제 status token entropy/평문 금지 계약이 다릅니다.")
    expected_lifecycle = {
        "ttlSeconds": 86400,
        "terminalRetentionSeconds": 86400,
        "replayCutoff": "nonterminal=statusTokenExpiresAt; terminal=min(statusTokenExpiresAt, terminalAt + 24h)",
        "replayGuarantee": "replay allowed iff now < replayCutoff; equality is not replayable",
        "replayCutoffAction": "delete status-token ciphertext and keyVersion; preserve irreversible verifier hash and encryptedAuthSubject",
        "verifierCutoff": "statusTokenExpiresAt + 24h",
        "verifierCutoffAction": "delete irreversible verifier hash and retained status; subsequent token is invalid 401",
        "expiryClassification": "expiresAt <= now < verifierCutoff returns 410; equality at verifierCutoff deletes hash and is invalid 401",
        "boundaryCases": [
            {"case": "nonterminal-before-expiry", "now": "statusTokenExpiresAt - 1 microsecond", "replay": True, "status": 200},
            {"case": "nonterminal-expiry-equality", "now": "statusTokenExpiresAt", "replay": False, "status": 410},
            {"case": "terminal-replay-cutoff-equality", "now": "min(statusTokenExpiresAt, terminalAt + 24h)", "replay": False, "verifierHash": "preserved until verifierCutoff"},
            {"case": "verifier-cutoff-equality", "now": "statusTokenExpiresAt + 24h", "replay": False, "status": 401},
        ],
        "keyRotation": "decrypt current keyVersion; re-encrypt with active key on successful replay",
        "cryptoFailure": "fail closed 503 without new token or raw crypto/provider cause",
    }
    if contract.get("deletionPolicy", {}).get("tokenLifecycle") != expected_lifecycle:
        errors.append("삭제 token replay/verifier lifecycle 경계가 다릅니다.")
    expected_worker_subject = {
        "tokenCleanupIndependence": "never deleted by status token replayCutoff or verifierCutoff",
        "nonterminalTokenExpiry": "preserve",
        "lateWorker": "preserve for late worker Auth retry",
        "deleteOnlyWhen": ["Auth deletion succeeded", "safe terminalization guarantees no future Auth retry"],
        "deleteAction": "remove encryptedAuthSubject in the same committed worker transition",
    }
    if contract.get("deletionPolicy", {}).get("workerAuthSubjectLifecycle") != expected_worker_subject:
        errors.append("worker auth subject lifecycle이 status token cleanup과 분리되지 않았습니다.")
    expected_capability = {
        "verification": "verifier hash lookup first; constant-time compare; dummy compare on no hash row",
        "precedence": [
            {"case": "missing-or-malformed-token", "status": 401, "code": "INVALID_DELETION_STATUS_TOKEN", "lookup": "no identifier existence result"},
            {"case": "unknown-token-hash", "status": 401, "code": "INVALID_DELETION_STATUS_TOKEN", "lookup": "dummy constant-time verification"},
            {"case": "valid-token-mismatched-or-missing-id", "status": 403, "code": "DELETION_STATUS_FORBIDDEN", "lookup": "do not reveal identifier existence"},
            {"case": "valid-token-matching-id-status-missing", "status": 404, "code": "PROFILE_RESOURCE_NOT_FOUND", "lookup": "capability already proves identifier knowledge"},
        ],
        "existenceConcealment": "same observable result for any unproven deletionRequestId",
    }
    if contract.get("deletionStatusPolicy", {}).get("capabilityAuthorization") != expected_capability:
        errors.append("삭제 status capability precedence/concealment 계약이 다릅니다.")
    expected_providers = {
        "allowed": ["google", "kakao", "custom:naver"],
        "normalization": "trim then Unicode-independent ASCII lowercase",
        "deduplication": "deduplicate after normalization",
        "stableOrder": ["google", "kakao", "custom:naver"],
        "emailIdentity": "exclude; email-only identity projects []",
        "unknown": "reject; never project free-form identity provider",
    }
    if contract.get("profileProviderPolicy") != expected_providers:
        errors.append("profile provider canonical projection 정책이 다릅니다.")
    provider_schema = (
        contract.get("schemas", {})
        .get("ProfileResponse", {})
        .get("properties", {})
        .get("providers", {})
    )
    if (
        provider_schema.get("minItems") != 0
        or provider_schema.get("uniqueItems") is not True
        or provider_schema.get("items", {}).get("enum") != expected_providers["allowed"]
    ):
        errors.append("email-only 공개 providers는 closed unique 빈 배열을 허용해야 합니다.")
    conditions = contract.get("errorConditions")
    if not isinstance(conditions, list) or {item.get("status") for item in conditions if isinstance(item, dict)} != {400, 401, 403, 404, 409, 410, 422, 428, 429, 503}:
        errors.append("오류 status condition matrix가 다릅니다.")
    else:
        for item in conditions:
            example = item.get("example")
            if not isinstance(example, dict) or set(example) != PROBLEM_FIELDS:
                errors.append("Problem Details 8-field closed example이 필요합니다.")
            elif item.get("status") != example.get("status") or item.get("code") != example.get("code"):
                errors.append("Problem Details status/code mapping이 다릅니다.")
            elif (
                example.get("traceId") != CANONICAL_PROBLEM_TRACE_ID
                or example.get("instance") != CANONICAL_PROBLEM_INSTANCE
            ):
                errors.append("Problem Details canonical traceId/instance placeholder가 다릅니다.")


def _validate_schema_value(value: Any, schema: dict[str, Any], path: str, errors: list[str]) -> None:
    if value is None:
        if schema.get("nullable") is not True:
            errors.append(f"{path} null을 허용하지 않습니다.")
        return
    kind = schema.get("type")
    if kind == "object":
        if not isinstance(value, dict):
            errors.append(f"{path} object가 필요합니다.")
            return
        properties = schema.get("properties")
        required = schema.get("required", [])
        if not isinstance(properties, dict) or not isinstance(required, list):
            errors.append(f"{path} object schema가 잘못됐습니다.")
            return
        missing = set(required) - set(value)
        if missing:
            errors.append(f"{path} required 누락: {sorted(missing)}")
        if schema.get("additionalProperties") is not False:
            errors.append(f"{path} additionalProperties=false가 필요합니다.")
        extra = set(value) - set(properties)
        if extra:
            errors.append(f"{path} extra fields: {sorted(extra)}")
        if len(value) < schema.get("minProperties", 0):
            errors.append(f"{path} minProperties 미만입니다.")
        for name, item in value.items():
            if name in properties:
                _validate_schema_value(item, properties[name], f"{path}.{name}", errors)
        return
    if kind == "array":
        if not isinstance(value, list):
            errors.append(f"{path} array가 필요합니다.")
            return
        if len(value) < schema.get("minItems", 0) or len(value) > schema.get("maxItems", sys.maxsize):
            errors.append(f"{path} array 길이가 범위를 벗어났습니다.")
        if schema.get("uniqueItems") is True and len({json.dumps(item, sort_keys=True) for item in value}) != len(value):
            errors.append(f"{path} array 값이 중복됐습니다.")
        item_schema = schema.get("items")
        if not isinstance(item_schema, dict):
            errors.append(f"{path} items schema가 필요합니다.")
            return
        for index, item in enumerate(value):
            _validate_schema_value(item, item_schema, f"{path}[{index}]", errors)
        return
    if kind == "string":
        if not isinstance(value, str):
            errors.append(f"{path} string이 필요합니다.")
            return
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", sys.maxsize):
            errors.append(f"{path} 문자열 길이가 범위를 벗어났습니다.")
        if "enum" in schema and value not in schema["enum"]:
            errors.append(f"{path} enum 값이 아닙니다.")
        if "pattern" in schema and re.fullmatch(schema["pattern"], value) is None:
            errors.append(f"{path} pattern과 일치하지 않습니다.")
        if schema.get("format") == "uuid":
            try:
                uuid.UUID(value)
            except (ValueError, AttributeError):
                errors.append(f"{path} UUID가 아닙니다.")
        if schema.get("format") == "date-time":
            try:
                if re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})", value) is None:
                    raise ValueError
                parsed = datetime.fromisoformat(value)
                if parsed.tzinfo is None:
                    raise ValueError
            except (ValueError, TypeError):
                errors.append(f"{path} offset date-time이 아닙니다.")
        return
    if kind == "boolean":
        if type(value) is not bool:
            errors.append(f"{path} boolean이 필요합니다.")
        elif "enum" in schema and value not in schema["enum"]:
            errors.append(f"{path} enum 값이 아닙니다.")
        return
    if kind == "integer":
        if type(value) is not int:
            errors.append(f"{path} integer가 필요합니다.")
            return
        if value < schema.get("minimum", value) or value > schema.get("maximum", value):
            errors.append(f"{path} integer 범위를 벗어났습니다.")
        if "enum" in schema and value not in schema["enum"]:
            errors.append(f"{path} enum 값이 아닙니다.")
        return
    if kind == "number":
        if type(value) not in {int, float} or not math.isfinite(value):
            errors.append(f"{path} number가 필요합니다.")
            return
        if value < schema.get("minimum", value) or value > schema.get("maximum", value):
            errors.append(f"{path} number 범위를 벗어났습니다.")
        if "enum" in schema and value not in schema["enum"]:
            errors.append(f"{path} enum 값이 아닙니다.")
        return
    errors.append(f"{path} 지원하지 않는 schema type입니다.")


def _validate_schema_meta(schema: Any, path: str, errors: list[str]) -> None:
    if not isinstance(schema, dict) or schema.get("type") not in {"object", "array", "string", "boolean", "integer", "number"}:
        errors.append(f"{path} schema constraint type이 잘못됐습니다.")
        return
    kind = schema["type"]
    allowed = {
        "object": {"type", "nullable", "additionalProperties", "required", "properties", "minProperties"},
        "array": {"type", "nullable", "items", "minItems", "maxItems", "uniqueItems"},
        "string": {"type", "nullable", "minLength", "maxLength", "pattern", "format", "enum"},
        "boolean": {"type", "nullable", "enum"},
        "integer": {"type", "nullable", "minimum", "maximum", "enum"},
        "number": {"type", "nullable", "minimum", "maximum", "enum"},
    }[kind] | {"readOnly"}
    unknown = set(schema) - allowed
    if unknown:
        errors.append(f"{path} schema keyword가 허용되지 않습니다: {sorted(unknown)}")
    if "nullable" in schema and type(schema["nullable"]) is not bool:
        errors.append(f"{path} schema constraint nullable은 boolean이어야 합니다.")
    if "readOnly" in schema and type(schema["readOnly"]) is not bool:
        errors.append(f"{path} schema constraint readOnly는 boolean이어야 합니다.")

    if kind == "object":
        properties = schema.get("properties")
        required = schema.get("required", [])
        if schema.get("additionalProperties") is not False or not isinstance(properties, dict):
            errors.append(f"{path} schema constraint closed object가 아닙니다.")
            return
        if not isinstance(required, list) or not all(isinstance(item, str) for item in required) or len(required) != len(set(required)):
            errors.append(f"{path} schema constraint required가 잘못됐습니다.")
            required = []
        if not set(required) <= set(properties):
            errors.append(f"{path} schema constraint required/properties가 다릅니다.")
        minimum = schema.get("minProperties", 0)
        if type(minimum) is not int or minimum < 0 or minimum > len(properties):
            errors.append(f"{path} schema constraint minProperties가 잘못됐습니다.")
        for name, child in properties.items():
            if not isinstance(name, str) or not name:
                errors.append(f"{path} schema keyword property 이름이 잘못됐습니다.")
            _validate_schema_meta(child, f"{path}.{name}", errors)
        return

    if kind == "array":
        minimum = schema.get("minItems", 0)
        maximum = schema.get("maxItems", sys.maxsize)
        if type(minimum) is not int or type(maximum) is not int or minimum < 0 or maximum < minimum:
            errors.append(f"{path} schema constraint item 범위가 잘못됐습니다.")
        if "uniqueItems" in schema and type(schema["uniqueItems"]) is not bool:
            errors.append(f"{path} schema constraint uniqueItems는 boolean이어야 합니다.")
        _validate_schema_meta(schema.get("items"), f"{path}.items", errors)
        return

    if kind == "string":
        minimum = schema.get("minLength", 0)
        maximum = schema.get("maxLength", sys.maxsize)
        if type(minimum) is not int or type(maximum) is not int or minimum < 0 or maximum < minimum:
            errors.append(f"{path} schema constraint 문자열 범위가 잘못됐습니다.")
        if "pattern" in schema:
            try:
                if not isinstance(schema["pattern"], str):
                    raise TypeError
                re.compile(schema["pattern"])
            except (TypeError, re.error):
                errors.append(f"{path} schema constraint pattern이 잘못됐습니다.")
        if "format" in schema and schema["format"] not in {"uuid", "date-time"}:
            errors.append(f"{path} schema constraint format이 잘못됐습니다.")
    elif kind in {"integer", "number"}:
        numeric_type = (int,) if kind == "integer" else (int, float)
        for keyword in ("minimum", "maximum"):
            if keyword in schema and (type(schema[keyword]) not in numeric_type or not math.isfinite(schema[keyword])):
                errors.append(f"{path} schema constraint {keyword}이 잘못됐습니다.")
        if (
            "minimum" in schema
            and "maximum" in schema
            and type(schema["minimum"]) in numeric_type
            and type(schema["maximum"]) in numeric_type
            and schema["minimum"] > schema["maximum"]
        ):
            errors.append(f"{path} schema constraint numeric 범위가 잘못됐습니다.")

    if "enum" in schema:
        enum = schema["enum"]
        if not isinstance(enum, list) or not enum or len({json.dumps(item, sort_keys=True) for item in enum}) != len(enum):
            errors.append(f"{path} schema constraint enum이 비었거나 중복됐습니다.")
        elif kind in {"string", "boolean", "integer", "number"}:
            expected_types = {"string": (str,), "boolean": (bool,), "integer": (int,), "number": (int, float)}[kind]
            if any(type(item) not in expected_types for item in enum):
                errors.append(f"{path} schema constraint enum type이 잘못됐습니다.")


def validate_contract_value(contract: Any) -> list[str]:
    errors: list[str] = []
    _validate_contract(contract, errors)
    if not isinstance(contract, dict):
        return errors
    schemas = contract.get("schemas")
    if not isinstance(schemas, dict):
        errors.append("schemas object가 필요합니다.")
        return errors
    canonical = _load(DEFAULT_CONTRACT).get("schemas")
    if schemas != canonical:
        errors.append("schema canonical definition이 변경되거나 느슨해졌습니다.")
    for name, schema in schemas.items():
        _validate_schema_meta(schema, f"schemas.{name}", errors)
    conditions = contract.get("errorConditions")
    endpoints = contract.get("endpoints")
    if isinstance(conditions, list) and isinstance(endpoints, list):
        by_code = {
            item.get("code"): item
            for item in conditions
            if isinstance(item, dict) and isinstance(item.get("code"), str)
        }
        used: set[str] = set()
        for endpoint in endpoints:
            if not isinstance(endpoint, dict):
                continue
            identity = f'{endpoint.get("method")} {endpoint.get("path")}'
            matrix = endpoint.get("errorMatrix")
            if not isinstance(matrix, dict):
                errors.append(f"{identity} errorMatrix가 필요합니다.")
                continue
            response_errors = endpoint.get("responses", {}).get("errors", [])
            for status_text, codes in matrix.items():
                if not isinstance(codes, list) or not codes:
                    errors.append(f"{identity} errorMatrix code가 필요합니다.")
                    continue
                try:
                    status = int(status_text)
                except (TypeError, ValueError):
                    errors.append(f"{identity} errorMatrix status가 잘못됐습니다.")
                    continue
                if status not in response_errors:
                    errors.append(f"{identity} response/errorMatrix status가 다릅니다.")
                for code in codes:
                    condition = by_code.get(code)
                    if condition is None or condition.get("status") != status:
                        errors.append(f"{identity} {code} status mapping이 다릅니다.")
                    elif identity not in condition.get("endpoints", []):
                        errors.append(f"{identity} {code} endpoint mapping이 다릅니다.")
                    used.add(code)
        if used != set(by_code):
            errors.append("global error condition과 endpoint matrix가 양방향 일치하지 않습니다.")
        for code, condition in by_code.items():
            for identity in condition.get("endpoints", []):
                matching = [
                    endpoint
                    for endpoint in endpoints
                    if f'{endpoint.get("method")} {endpoint.get("path")}' == identity
                ]
                if len(matching) != 1 or code not in {
                    item
                    for codes in matching[0].get("errorMatrix", {}).values()
                    if isinstance(codes, list)
                    for item in codes
                }:
                    errors.append(f"{code} global endpoint mapping이 다릅니다.")
    return errors


def validate_deletion_status(body: Any) -> list[str]:
    errors: list[str] = []
    if not isinstance(body, dict) or set(body) != {"deletionRequestId", "status", "currentStep", "nextRetryAt", "completedAt"}:
        return ["삭제 상태 response exact 필드가 다릅니다."]
    state = body.get("status")
    rules = {
        "queued": {"currentStep": None, "completedAt": None},
        "running": {"currentStep": "required", "completedAt": None},
        "succeeded": {"currentStep": None, "nextRetryAt": None, "completedAt": "required"},
        "failed": {"currentStep": None, "completedAt": "required"},
        "cancelled": {"currentStep": None, "nextRetryAt": None, "completedAt": "required"},
    }
    if state not in rules:
        return ["삭제 상태 discriminator가 다릅니다."]
    for field, expected in rules[state].items():
        value = body.get(field)
        if expected is None and value is not None:
            errors.append(f"{state}.{field}는 null이어야 합니다.")
        elif expected == "required" and (not isinstance(value, str) or not value):
            errors.append(f"{state}.{field}는 required입니다.")
    return errors


def _validate_provider_projection_examples(examples: Any, contract: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    expected_cases = ["email-only", "single-oauth", "normalized-deduplicated-multiple"]
    if not isinstance(examples, list):
        return ["provider projection fixture가 필요합니다."]
    cases = [item.get("case") for item in examples if isinstance(item, dict)]
    if cases != expected_cases or len(examples) != len(expected_cases):
        errors.append("provider projection fixture는 email-only/single/multiple을 정확히 1회씩 포함해야 합니다.")
    provider_policy = contract.get("profileProviderPolicy", {})
    allowed = provider_policy.get("allowed", [])
    stable_order = provider_policy.get("stableOrder", [])
    for index, item in enumerate(examples):
        if not isinstance(item, dict) or set(item) != {"case", "sourceProviders", "providers"}:
            errors.append(f"provider projection[{index}] exact fixture shape가 다릅니다.")
            continue
        source = item.get("sourceProviders")
        projected = item.get("providers")
        if not isinstance(source, list) or not source or not all(isinstance(value, str) for value in source):
            errors.append(f"provider projection[{index}] sourceProviders가 다릅니다.")
            continue
        normalized = [value.strip().lower() for value in source if value.isascii()]
        if len(normalized) != len(source) or any(value not in [*allowed, "email"] for value in normalized):
            errors.append(f"provider projection[{index}] unknown source provider를 허용하지 않습니다.")
            continue
        expected_projection = [value for value in stable_order if value in set(normalized)]
        if projected != expected_projection:
            errors.append(f"provider projection[{index}] 공개 providers 정규화/dedupe/stable order가 다릅니다.")
    return errors


def validate_fixture_value(kind: str, fixture: Any, contract: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if not isinstance(fixture, dict) or not isinstance(fixture.get("examples"), list):
        return [f"{kind} fixture examples가 필요합니다."]
    endpoints = {(item["method"], item["path"]): item for item in contract["endpoints"]}
    schemas = contract["schemas"]
    if kind == "problem":
        condition_by_code = {item["code"]: item for item in contract["errorConditions"]}
        endpoint_by_identity = {
            f'{item["method"]} {item["path"]}': item for item in contract["endpoints"]
        }
        seen: set[tuple[Any, Any]] = set()
        for index, example in enumerate(fixture["examples"]):
            if not isinstance(example, dict) or set(example) != {"endpoint", "body"}:
                errors.append(f"problem[{index}] exact fixture shape가 다릅니다.")
                continue
            body = example.get("body")
            _validate_schema_value(body, schemas["ProblemDetails"], f"problem[{index}].body", errors)
            condition = condition_by_code.get(body.get("code")) if isinstance(body, dict) else None
            if condition is None or example["endpoint"] not in condition.get("endpoints", []):
                errors.append(f"problem[{index}] endpoint/code mapping이 다릅니다.")
            elif body != condition.get("example"):
                errors.append(f"problem[{index}] canonical example exact fields가 다릅니다.")
            endpoint = endpoint_by_identity.get(example["endpoint"])
            if endpoint is None or not isinstance(body, dict) or body.get("status") not in endpoint["responses"]["errors"]:
                errors.append(f"problem[{index}] status가 endpoint errors에 없습니다.")
            pair = (example["endpoint"], body.get("code") if isinstance(body, dict) else None)
            if pair in seen:
                errors.append(f"problem[{index}] endpoint/code fixture가 중복됐습니다.")
            seen.add(pair)
        return errors
    identities: list[tuple[Any, Any]] = []
    for index, example in enumerate(fixture["examples"]):
        if not isinstance(example, dict):
            errors.append(f"{kind}[{index}] object가 필요합니다.")
            continue
        identity = (example.get("method"), example.get("contractPath"))
        identities.append(identity)
        endpoint = endpoints.get(identity)
        if endpoint is None:
            errors.append(f"{kind}[{index}] endpoint가 계약에 없습니다.")
            continue
        allowed_keys = {"method", "contractPath", "path"}
        if kind == "success":
            allowed_keys |= {"status", "body"}
        elif kind == "request":
            allowed_keys |= {
                location
                for location in ("query", "headers", "body")
                if endpoint["schemas"][location] != "none"
            }
        if set(example) - allowed_keys:
            errors.append(f"{kind}[{index}] exact fixture keys가 다릅니다.")

        template = endpoint["path"]
        actual_path = example.get("path")
        parameter_names = re.findall(r"\{([A-Za-z][A-Za-z0-9]*)\}", template)
        pattern = re.escape(template)
        for name in parameter_names:
            pattern = pattern.replace(re.escape("{" + name + "}"), f"(?P<{name}>[^/]+)")
        match = re.fullmatch(pattern, actual_path) if isinstance(actual_path, str) else None
        if match is None:
            errors.append(f"{kind}[{index}] 실제 path가 contractPath와 일치하지 않습니다.")
        elif endpoint["schemas"]["path"] != "none":
            _validate_schema_value(match.groupdict(), schemas[endpoint["schemas"]["path"]], f"{kind}[{index}].path", errors)

        if kind == "success":
            if type(example.get("status")) is not int or example.get("status") not in endpoint["responses"]["success"]:
                errors.append(f"success[{index}] status가 endpoint success에 없습니다.")
            _validate_schema_value(example.get("body"), schemas[endpoint["successSchema"]], f"success[{index}].body", errors)
            if identity == ("GET", "/api/v1/account-deletion-requests/{deletionRequestId}"):
                errors.extend(validate_deletion_status(example.get("body")))
            if identity in {("GET", "/api/v1/me"), ("PATCH", "/api/v1/me")} and isinstance(example.get("body"), dict):
                provider_order = contract["profileProviderPolicy"]["stableOrder"]
                providers = example["body"].get("providers", [])
                expected = (
                    sorted(set(providers), key=provider_order.index)
                    if isinstance(providers, list)
                    and all(isinstance(item, str) and item in provider_order for item in providers)
                    else []
                )
                if providers != expected:
                    errors.append(f"success[{index}] providers 정규화/dedupe/stable order가 다릅니다.")
        elif kind == "request":
            for location in ("headers", "query", "body"):
                schema_name = endpoint["schemas"][location]
                if schema_name == "none":
                    continue
                schema = schemas[schema_name]
                if location not in example and schema.get("required"):
                    errors.append(f"request[{index}].{location}가 필요합니다.")
                elif location in example:
                    value = example[location]
                    if (
                        location == "headers"
                        and isinstance(value, dict)
                        and value.get("Authorization") == "Bearer <fixture-access-token>"
                    ):
                        value = {**value, "Authorization": "Bearer " + ("a" * 16)}
                    _validate_schema_value(value, schema, f"request[{index}].{location}", errors)
            if identity == ("PUT", "/api/v1/me/consents") and isinstance(example.get("body"), dict):
                consent_ids = [item.get("documentId") for item in example["body"].get("consents", []) if isinstance(item, dict)]
                if len(consent_ids) != len(set(consent_ids)):
                    errors.append("consent documentId가 중복됐습니다.")
    if set(identities) != set(endpoints) or len(identities) != len(endpoints):
        errors.append(f"{kind} fixture는 endpoint마다 정확히 1개여야 합니다.")
    if kind == "success":
        errors.extend(_validate_provider_projection_examples(fixture.get("providerProjectionExamples"), contract))
        states = []
        for item in fixture.get("deletionStatusExamples", []):
            if not isinstance(item, dict) or set(item) != {"body"}:
                errors.append("삭제 상태 fixture exact key가 다릅니다.")
            body = item.get("body") if isinstance(item, dict) else None
            if isinstance(body, dict):
                states.append(body.get("status"))
            _validate_schema_value(body, schemas["DeletionStatusResponse"], "deletionStatusExamples.body", errors)
            errors.extend(validate_deletion_status(body))
        if states != contract["deletionStatusPolicy"]["states"]:
            errors.append("삭제 상태 fixture는 five-state를 정확히 1회씩 포함해야 합니다.")
    return errors


def _validate_catalog(contract: dict[str, Any], errors: list[str]) -> None:
    catalog = _load(CATALOG)
    actual = {(item["method"], item["path"]): item for item in catalog.get("endpoints", [])}
    for endpoint in contract.get("endpoints", []):
        identity = (endpoint["method"], endpoint["path"])
        if actual.get(identity) != catalog_projection(endpoint):
            errors.append(f"catalog projection drift: {identity[0]} {identity[1]}")
    domains = [item for item in catalog.get("domainContracts", []) if item.get("issue") == 82]
    if len(domains) != 1 or domains[0].get("versions") != {"local": "1.0.0", "notion": "not-linked", "figma": "not-linked"}:
        errors.append("catalog #82 version/readiness lineage가 다릅니다.")
    elif domains[0].get("readiness", {}).get("implementation") != {
        "status": "not-ready",
        "evidence": None,
        "implementedBy": [18, 19],
        "blockedBy": [61, 106],
    }:
        errors.append("catalog implementation readiness를 승격할 수 없습니다.")


def _validate_fixtures(errors: list[str]) -> None:
    contract = _load(DEFAULT_CONTRACT)
    expected = {"request": {"contractVersion", "examples"}, "success": {"contractVersion", "examples", "providerProjectionExamples", "deletionStatusExamples"}, "problem": {"contractVersion", "examples"}}
    for name, fields in expected.items():
        path = FIXTURES / f"{name}.json"
        fixture = _load(path)
        if not isinstance(fixture, dict) or set(fixture) != fields or fixture.get("contractVersion") != "1.0.0":
            errors.append(f"{name} fixture closed top-level 계약이 다릅니다.")
            continue
        examples = fixture.get("examples")
        if not isinstance(examples, list) or not examples:
            errors.append(f"{name} fixture examples가 필요합니다.")
        source = path.read_text(encoding="utf-8")
        if FORBIDDEN_TEXT.search(source):
            errors.append(f"{name} fixture에 credential/raw provider 정보가 있습니다.")
        errors.extend(validate_fixture_value(name, fixture, contract))
    problems = _load(FIXTURES / "problem.json").get("examples", [])
    for item in problems:
        if not isinstance(item, dict) or set(item) != {"endpoint", "body"} or set(item.get("body", {})) != PROBLEM_FIELDS:
            errors.append("problem fixture example shape가 다릅니다.")
            continue
        matching = [condition for condition in contract["errorConditions"] if condition["code"] == item["body"].get("code")]
        if len(matching) != 1 or item["endpoint"] not in matching[0]["endpoints"]:
            errors.append("problem fixture endpoint/code mapping이 다릅니다.")
        elif item["body"] != matching[0].get("example"):
            errors.append("problem fixture canonical example exact fields가 다릅니다.")
        _validate_schema_value(item["body"], contract["schemas"]["ProblemDetails"], "problem.body", errors)


def validate(path: Path) -> list[str]:
    contract = _load(path)
    errors = validate_contract_value(contract)
    if isinstance(contract, dict):
        _validate_catalog(contract, errors)
    _validate_fixtures(errors)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    args = parser.parse_args()
    try:
        errors = validate(args.contract)
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        print("프로필·법정 문서 계약을 읽거나 검증할 수 없습니다.", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(f"프로필·법정 문서 계약 오류: {error}", file=sys.stderr)
        return 1
    print("프로필·법정 문서 계약 검사 성공")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
