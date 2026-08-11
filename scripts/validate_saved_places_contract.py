#!/usr/bin/env python3
"""Issue #84 관심 장소 CRUD machine contract와 fixture를 엄격히 검사한다."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import unicodedata
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CONTRACT_RELATIVE = Path("docs/contracts/domains/saved-places/contract.json")
CATALOG_RELATIVE = Path("docs/contracts/rest/catalog.json")
FIXTURE_ROOT_RELATIVE = Path("fixtures/contracts/saved-places")
CONTRACT_FIELDS = {
    "schemaVersion",
    "contractVersion",
    "sourceSpecVersion",
    "inherits",
    "ownerIssue",
    "implementationIssue",
    "schemas",
    "endpoints",
    "pagination",
    "createSemantics",
    "patchSemantics",
    "deleteSemantics",
    "ownership",
    "storage",
    "externalTraceability",
    "readiness",
}
CANONICAL_CONTRACT_SHA256 = "b8356d704a1f0bde3ed76369c7668307272d6164d7dae097a6aa577cbd8527fa"
CANONICAL_CATALOG_SHA256 = "ba79e708b1efc0ef504fa11b213f59d20ce831abe994719f227b82946c7b9fd5"
EXPECTED_ENDPOINT_IDENTITIES = [
    ("GET", "/api/v1/me/saved-places"),
    ("POST", "/api/v1/me/saved-places"),
    ("PATCH", "/api/v1/me/saved-places/{placeId}"),
    ("DELETE", "/api/v1/me/saved-places/{placeId}"),
]
EXPECTED_PROBLEMS = {
    "400_invalid_request": (
        400,
        "INVALID_REQUEST",
        "https://api.timing-jeju.com/problems/invalid-request",
    ),
    "401_authentication_required": (
        401,
        "AUTHENTICATION_REQUIRED",
        "https://api.timing-jeju.com/problems/authentication-required",
    ),
    "401_invalid_access_token": (
        401,
        "INVALID_ACCESS_TOKEN",
        "https://api.timing-jeju.com/problems/invalid-access-token",
    ),
    "404_place_not_found": (
        404,
        "PLACE_NOT_FOUND",
        "https://api.timing-jeju.com/problems/place-not-found",
    ),
    "404_saved_place_not_found": (
        404,
        "SAVED_PLACE_NOT_FOUND",
        "https://api.timing-jeju.com/problems/saved-place-not-found",
    ),
    "409_idempotency_payload_conflict": (
        409,
        "IDEMPOTENCY_PAYLOAD_CONFLICT",
        "https://api.timing-jeju.com/problems/idempotency-payload-conflict",
    ),
    "409_saved_place_already_exists": (
        409,
        "SAVED_PLACE_ALREADY_EXISTS",
        "https://api.timing-jeju.com/problems/saved-place-already-exists",
    ),
    "409_saved_place_version_conflict": (
        409,
        "SAVED_PLACE_VERSION_CONFLICT",
        "https://api.timing-jeju.com/problems/saved-place-version-conflict",
    ),
    "422_saved_place_constraint_violation": (
        422,
        "SAVED_PLACE_CONSTRAINT_VIOLATION",
        "https://api.timing-jeju.com/problems/saved-place-constraint-violation",
    ),
}


class ContractValidationError(ValueError):
    pass


def _reject_constant(value: str) -> None:
    raise ContractValidationError(f"JSON 표준 밖 숫자 {value}는 허용되지 않습니다.")


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractValidationError(f"중복 JSON key {key}는 허용되지 않습니다.")
        result[key] = value
    return result


def _load_json(path: Path) -> Any:
    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            parse_constant=_reject_constant,
            object_pairs_hook=_reject_duplicate_pairs,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ContractValidationError) as exc:
        raise ContractValidationError(f"{path}: strict JSON을 읽을 수 없습니다: {exc}") from exc


def _canonical_digest(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _catalog_projection(catalog: dict[str, Any]) -> dict[str, Any]:
    domain = [
        item
        for item in catalog.get("domainContracts", [])
        if isinstance(item, dict) and item.get("domain") == "saved-places"
    ]
    endpoints = [
        endpoint
        for endpoint in catalog.get("endpoints", [])
        if isinstance(endpoint, dict)
        and endpoint.get("path")
        in {"/api/v1/me/saved-places", "/api/v1/me/saved-places/{placeId}"}
    ]
    return {"domainContracts": domain, "endpoints": endpoints}


def validate(root: Path) -> list[str]:
    errors: list[str] = []
    try:
        contract = _load_json(root / CONTRACT_RELATIVE)
        catalog = _load_json(root / CATALOG_RELATIVE)
        request = _load_json(root / FIXTURE_ROOT_RELATIVE / "request.json")
        success = _load_json(root / FIXTURE_ROOT_RELATIVE / "success.json")
        problems = _load_json(root / FIXTURE_ROOT_RELATIVE / "problem.json")
    except ContractValidationError as exc:
        return [str(exc)]

    if not isinstance(contract, dict):
        return ["관심 장소 contract 최상위는 object여야 합니다."]
    unknown = set(contract) - CONTRACT_FIELDS
    missing = CONTRACT_FIELDS - set(contract)
    if unknown:
        errors.append(
            "관심 장소 contract에 허용되지 않은 필드가 있습니다: "
            + ", ".join(sorted(unknown))
        )
    if missing:
        errors.append(
            "관심 장소 contract 필수 필드가 없습니다: " + ", ".join(sorted(missing))
        )
    if _canonical_digest(contract) != CANONICAL_CONTRACT_SHA256:
        errors.append("관심 장소 contract canonical schema 또는 endpoint 의미가 변경되었습니다.")

    if not isinstance(catalog, dict):
        errors.append("REST catalog는 object여야 합니다.")
    elif _canonical_digest(_catalog_projection(catalog)) != CANONICAL_CATALOG_SHA256:
        errors.append("관심 장소 catalog canonical endpoint/readiness가 변경되었습니다.")

    schemas = contract.get("schemas")
    if not isinstance(schemas, dict):
        errors.append("관심 장소 schemas는 object여야 합니다.")
        return errors

    identities = [
        (endpoint.get("method"), endpoint.get("path"))
        for endpoint in contract.get("endpoints", [])
        if isinstance(endpoint, dict)
    ]
    if identities != EXPECTED_ENDPOINT_IDENTITIES:
        errors.append("관심 장소 endpoint method/path canonical 순서가 다릅니다.")

    _validate_request_fixture(request, schemas, errors)
    _validate_success_fixture(success, schemas, errors)
    _validate_problem_fixture(problems, schemas, errors)
    _validate_external_readiness(contract, errors)
    return errors


def _validate_request_fixture(
    fixture: Any, schemas: dict[str, Any], errors: list[str]
) -> None:
    label = "request fixture"
    if not isinstance(fixture, dict) or set(fixture) != {
        "contractVersion",
        "list",
        "create",
        "patch",
        "delete",
    }:
        errors.append(f"{label} 최상위 구조가 정확하지 않습니다.")
        return
    if fixture.get("contractVersion") != "1.0.0":
        errors.append(f"{label} contractVersion이 다릅니다.")
    expected = {
        "list": ("GET", "/api/v1/me/saved-places"),
        "create": ("POST", "/api/v1/me/saved-places"),
        "patch": (
            "PATCH",
            "/api/v1/me/saved-places/20000000-0000-0000-0000-000000000003",
        ),
        "delete": (
            "DELETE",
            "/api/v1/me/saved-places/20000000-0000-0000-0000-000000000003",
        ),
    }
    for name, (method, path) in expected.items():
        request = fixture.get(name)
        if not isinstance(request, dict):
            errors.append(f"{label}.{name}은 object여야 합니다.")
            continue
        if request.get("method") != method or request.get("path") != path:
            errors.append(f"{label}.{name} method/path가 canonical 계약과 다릅니다.")
        headers = request.get("headers")
        if not isinstance(headers, dict) or headers.get("Authorization") != "Bearer <fixture-access-token>":
            errors.append(f"{label}.{name} Authorization fixture가 정확하지 않습니다.")

    list_request = fixture.get("list", {})
    _validate_value(
        list_request.get("query"), schemas.get("SavedPlacesListQuery"), schemas, f"{label}.list.query", errors
    )
    create = fixture.get("create", {})
    _validate_value(create.get("body"), schemas.get("CreateSavedPlaceRequest"), schemas, f"{label}.create.body", errors)
    create_headers = create.get("headers")
    if not isinstance(create_headers, dict) or set(create_headers) != {"Authorization", "Idempotency-Key"}:
        errors.append(f"{label}.create headers는 Authorization과 Idempotency-Key만 가져야 합니다.")
    else:
        _validate_value(
            {"Idempotency-Key": create_headers["Idempotency-Key"]},
            schemas.get("CreateSavedPlaceHeaders"),
            schemas,
            f"{label}.create.headers",
            errors,
        )

    patch = fixture.get("patch", {})
    _validate_concrete_path_request(patch, schemas, f"{label}.patch", errors)
    _validate_value(patch.get("body"), schemas.get("PatchSavedPlaceRequest"), schemas, f"{label}.patch.body", errors)
    patch_headers = patch.get("headers")
    if not isinstance(patch_headers, dict) or set(patch_headers) != {"Authorization", "If-Match"}:
        errors.append(f"{label}.patch headers는 Authorization과 If-Match만 가져야 합니다.")
    else:
        _validate_value(
            {"If-Match": patch_headers["If-Match"]},
            schemas.get("PatchSavedPlaceHeaders"),
            schemas,
            f"{label}.patch.headers",
            errors,
        )

    delete = fixture.get("delete", {})
    _validate_concrete_path_request(delete, schemas, f"{label}.delete", errors)
    if "body" in delete:
        errors.append(f"{label}.delete는 body를 가질 수 없습니다.")


def _validate_concrete_path_request(
    request: Any, schemas: dict[str, Any], label: str, errors: list[str]
) -> None:
    if not isinstance(request, dict):
        return
    parameters = request.get("pathParameters")
    _validate_value(parameters, schemas.get("SavedPlacePath"), schemas, f"{label}.pathParameters", errors)
    place_id = parameters.get("placeId") if isinstance(parameters, dict) else None
    expected_suffix = f"/{place_id}" if isinstance(place_id, str) else ""
    if not isinstance(request.get("path"), str) or not request["path"].endswith(expected_suffix):
        errors.append(f"{label} path와 placeId가 일치하지 않습니다.")


def _validate_success_fixture(
    fixture: Any, schemas: dict[str, Any], errors: list[str]
) -> None:
    label = "success fixture"
    if not isinstance(fixture, dict) or set(fixture) != {
        "list",
        "create",
        "createReplay",
        "createExisting",
        "patch",
        "delete",
    }:
        errors.append(f"{label} 최상위 구조가 정확하지 않습니다.")
        return
    _validate_value(fixture.get("list"), schemas.get("SavedPlacesListResponse"), schemas, f"{label}.list", errors)
    for name in ("create", "patch"):
        response = fixture.get(name)
        if not isinstance(response, dict):
            errors.append(f"{label}.{name}은 object여야 합니다.")
            continue
        _validate_value(response.get("body"), schemas.get("SavedPlace"), schemas, f"{label}.{name}.body", errors)
    create = fixture.get("create", {})
    if create.get("status") != 201 or create.get("headers") != {
        "Location": "/api/v1/me/saved-places/20000000-0000-0000-0000-000000000003",
        "ETag": '"saved-place-fixture-v1"',
        "Idempotency-Replayed": "false",
    }:
        errors.append(f"{label}.create status/headers가 canonical 계약과 다릅니다.")
    for name, status in (("createReplay", 201), ("createExisting", 200)):
        response = fixture.get(name, {})
        expected_headers = {
            "Location": "/api/v1/me/saved-places/20000000-0000-0000-0000-000000000003",
            "ETag": '"saved-place-fixture-v1"',
            "Idempotency-Replayed": "true",
        }
        if response != {"status": status, "headers": expected_headers, "bodyRef": "create.body"}:
            errors.append(f"{label}.{name} replay 계약이 정확하지 않습니다.")
    patch = fixture.get("patch", {})
    if patch.get("status") != 200 or patch.get("headers") != {"ETag": '"saved-place-fixture-v2"'}:
        errors.append(f"{label}.patch status/ETag가 정확하지 않습니다.")
    if fixture.get("delete") != {"status": 204, "body": None}:
        errors.append(f"{label}.delete는 204와 null body여야 합니다.")

    list_value = fixture.get("list")
    if isinstance(list_value, dict):
        _validate_cursor_semantic(list_value.get("page"), errors)
        for index, item in enumerate(list_value.get("items", [])):
            _validate_saved_place_semantic(item, f"{label}.list.items[{index}]", errors)
    for name in ("create", "patch"):
        response = fixture.get(name)
        if isinstance(response, dict):
            _validate_saved_place_semantic(response.get("body"), f"{label}.{name}.body", errors)


def _validate_cursor_semantic(page: Any, errors: list[str]) -> None:
    if not isinstance(page, dict):
        return
    has_next = page.get("hasNext")
    cursor = page.get("nextCursor")
    if has_next is True and not isinstance(cursor, str):
        errors.append("success fixture semantic: hasNext=true이면 nextCursor가 필요합니다.")
    if has_next is False and cursor is not None:
        errors.append("success fixture semantic: hasNext=false이면 nextCursor는 null이어야 합니다.")


def _validate_saved_place_semantic(value: Any, label: str, errors: list[str]) -> None:
    if not isinstance(value, dict):
        return
    try:
        saved_at = _parse_datetime(value.get("savedAt"))
        updated_at = _parse_datetime(value.get("updatedAt"))
    except ValueError:
        return
    if updated_at < saved_at:
        errors.append(f"success fixture semantic: {label}.updatedAt은 savedAt보다 빠를 수 없습니다.")


def _validate_problem_fixture(
    fixture: Any, schemas: dict[str, Any], errors: list[str]
) -> None:
    label = "problem fixture"
    if not isinstance(fixture, dict) or set(fixture) != set(EXPECTED_PROBLEMS):
        errors.append(f"{label} condition key 집합이 canonical 계약과 다릅니다.")
        return
    for key, (status, code, problem_type) in EXPECTED_PROBLEMS.items():
        value = fixture.get(key)
        _validate_value(value, schemas.get("ProblemDetails"), schemas, f"{label}.{key}", errors)
        if not isinstance(value, dict) or (
            value.get("status"), value.get("code"), value.get("type")
        ) != (status, code, problem_type):
            errors.append(f"{label}.{key} status/code/type이 canonical 조건과 다릅니다.")


def _validate_external_readiness(contract: dict[str, Any], errors: list[str]) -> None:
    external = contract.get("externalTraceability")
    readiness = contract.get("readiness")
    if not isinstance(external, dict) or not isinstance(readiness, dict):
        return
    for source in ("notion", "figma"):
        evidence = external.get(source)
        if not isinstance(evidence, dict) or evidence.get("status") != "not-ready" or evidence.get("contractVersion") != "not-linked":
            errors.append(f"external readiness semantic: {source}는 not-linked/not-ready여야 합니다.")
    if readiness != {
        "metadata": "not-ready",
        "example": "not-ready",
        "implementation": "not-ready",
        "reason": "Notion path/version/status drift and Figma contract version/state evidence missing",
    }:
        errors.append("external readiness semantic: readiness prerequisite가 정확하지 않습니다.")


def _validate_value(
    value: Any,
    schema: Any,
    schemas: dict[str, Any],
    label: str,
    errors: list[str],
) -> None:
    if not isinstance(schema, dict):
        errors.append(f"fixture schema {label}을 찾을 수 없습니다.")
        return
    if value is None:
        if schema.get("nullable") is True:
            return
        errors.append(f"fixture {label}은 null일 수 없습니다.")
        return
    reference = schema.get("$ref")
    if isinstance(reference, str):
        target = schemas.get(reference)
        if not isinstance(target, dict):
            errors.append(f"fixture {label}의 $ref {reference}를 찾을 수 없습니다.")
            return
        _validate_value(value, target, schemas, label, errors)
        return
    schema_type = schema.get("type")
    if schema_type == "object":
        if not isinstance(value, dict):
            errors.append(f"fixture {label}은 object여야 합니다.")
            return
        properties = schema.get("properties", {})
        required = schema.get("required", [])
        if schema.get("additionalProperties") is False:
            for key in set(value) - set(properties):
                errors.append(f"fixture {label}.{key}는 허용되지 않은 property입니다.")
        for key in required:
            if key not in value:
                errors.append(f"fixture {label}.{key} required property가 없습니다.")
        minimum = schema.get("minProperties")
        if type(minimum) is int and len(value) < minimum:
            errors.append(f"fixture {label}은 최소 {minimum}개 property가 필요합니다.")
        for key, item in value.items():
            if key in properties:
                _validate_value(item, properties[key], schemas, f"{label}.{key}", errors)
        return
    if schema_type == "array":
        if not isinstance(value, list):
            errors.append(f"fixture {label}은 array여야 합니다.")
            return
        maximum = schema.get("maxItems")
        if type(maximum) is int and len(value) > maximum:
            errors.append(f"fixture {label} item 수가 {maximum}을 초과합니다.")
        if schema.get("uniqueItems") is True:
            encoded = [json.dumps(item, ensure_ascii=False, sort_keys=True) for item in value]
            if len(encoded) != len(set(encoded)):
                errors.append(f"fixture {label} item은 unique여야 합니다.")
        if schema.get("canonicalOrder") == "unicode-codepoint-asc" and value != sorted(value):
            errors.append(f"fixture {label} item은 canonical 정렬이어야 합니다.")
        for index, item in enumerate(value):
            _validate_value(item, schema.get("items"), schemas, f"{label}[{index}]", errors)
        return
    if schema_type == "string":
        if not isinstance(value, str):
            errors.append(f"fixture {label}은 string이어야 합니다.")
            return
        if type(schema.get("minLength")) is int and len(value) < schema["minLength"]:
            errors.append(f"fixture {label} 문자열이 너무 짧습니다.")
        if type(schema.get("maxLength")) is int and len(value) > schema["maxLength"]:
            errors.append(f"fixture {label} 문자열이 너무 깁니다.")
        pattern = schema.get("pattern")
        if isinstance(pattern, str) and re.fullmatch(pattern, value) is None:
            errors.append(f"fixture {label} 문자열 pattern이 올바르지 않습니다.")
        allowed = schema.get("enum")
        if isinstance(allowed, list) and value not in allowed:
            errors.append(f"fixture {label} enum 값이 올바르지 않습니다.")
        normalization = schema.get("normalization")
        if normalization == "trim" and value != value.strip():
            errors.append(f"fixture {label}은 trim canonical 값이어야 합니다.")
        if normalization == "trim+nfc" and value != unicodedata.normalize("NFC", value.strip()):
            errors.append(f"fixture {label}은 trim+nfc canonical 값이어야 합니다.")
        _validate_string_format(value, schema.get("format"), label, errors)
        return
    if schema_type == "integer":
        if type(value) is not int:
            errors.append(f"fixture {label}은 strict integer여야 합니다.")
            return
        _validate_number_bounds(value, schema, label, errors)
        if isinstance(schema.get("enum"), list) and value not in schema["enum"]:
            errors.append(f"fixture {label} enum 정수가 올바르지 않습니다.")
        return
    if schema_type == "number":
        if type(value) not in (int, float) or not math.isfinite(value):
            errors.append(f"fixture {label}은 finite number여야 합니다.")
            return
        _validate_number_bounds(value, schema, label, errors)
        return
    if schema_type == "boolean" and type(value) is not bool:
        errors.append(f"fixture {label}은 boolean이어야 합니다.")


def _validate_number_bounds(
    value: int | float, schema: dict[str, Any], label: str, errors: list[str]
) -> None:
    minimum = schema.get("minimum")
    maximum = schema.get("maximum")
    if type(minimum) in (int, float) and value < minimum:
        errors.append(f"fixture {label}은 minimum {minimum} 이상이어야 합니다.")
    if type(maximum) in (int, float) and value > maximum:
        errors.append(f"fixture {label}은 maximum {maximum} 이하여야 합니다.")


def _validate_string_format(
    value: str, value_format: Any, label: str, errors: list[str]
) -> None:
    try:
        if value_format == "uuid":
            if str(uuid.UUID(value)) != value:
                raise ValueError("canonical UUID가 아님")
        elif value_format == "date-time":
            _parse_datetime(value)
        elif value_format == "uri":
            _validate_uri(value)
        elif value_format == "urn":
            if re.fullmatch(r"urn:timing-jeju:problem:[0-9a-f]{32}", value) is None:
                raise ValueError("problem URN이 아님")
        elif value_format == "trace-id":
            if re.fullmatch(r"[0-9a-f]{32}", value) is None:
                raise ValueError("trace id가 아님")
    except (ValueError, UnicodeError):
        errors.append(f"fixture {label}의 {value_format} format이 올바르지 않습니다.")


def _parse_datetime(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ValueError("string 아님")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("timezone 없음")
    return parsed


def _validate_uri(value: str) -> None:
    if any(character.isspace() for character in value):
        raise ValueError("whitespace")
    if re.search(r"%(?![0-9A-Fa-f]{2})", value):
        raise ValueError("percent escape")
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("absolute HTTPS URI 아님")
    _ = parsed.port


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPOSITORY_ROOT)
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    errors = validate(args.root.resolve())
    if errors:
        print("관심 장소 CRUD 계약 검사 실패")
        for error in errors:
            print(f"- {error}")
        return 1
    print("관심 장소 CRUD 계약 검사 성공")
    return 0


if __name__ == "__main__":
    sys.exit(main())
