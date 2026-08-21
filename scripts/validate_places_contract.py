#!/usr/bin/env python3
"""Issue #83 장소 검색·상세 계약과 추적성 fixture를 검사한다."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit
from uuid import UUID


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/places/contract.json"
EXPECTED_ENDPOINTS = {
    ("GET", "/api/v1/places"),
    ("GET", "/api/v1/places/{placeId}"),
}
PROVENANCE_FIELDS = {"owner", "provider", "observedAt", "expiresAt", "stale"}
EXPECTED_LIST_FIELDS = {
    "placeId",
    "contentId",
    "name",
    "category",
    "regionCode",
    "regionLabel",
    "address",
    "location",
    "thumbnailUrl",
    "recommendedStayMinutes",
    "recommendedStaySource",
    "recommendedStayPolicyVersion",
    "recommendedStayEffectiveAt",
    "recommendedStayUpdatedAt",
    "operationsSummary",
    "distanceMeters",
    "dataFreshness",
    "saved",
    "memo",
    "tags",
}
EXPECTED_DETAIL_SHARED_FIELDS = EXPECTED_LIST_FIELDS - {
    "distanceMeters",
    "dataFreshness",
    "memo",
    "tags",
}
EXPECTED_NEARBY_FIELDS = [
    "stopId",
    "stopName",
    "distanceMeters",
    "walkMinutes",
    "linkMethod",
    "provider",
    "observedAt",
    "expiresAt",
    "stale",
]
EXPECTED_ELIGIBILITY = [
    "place_stop_links.enabled = true",
    "place_stop_links.tombstoned_at IS NULL",
    "bus_stops.tombstoned_at IS NULL",
    "bus_stops.source_deleted_at IS NULL",
    "distance_meters <= configured maximum",
]
EXPECTED_SORT = [
    "stale ASC",
    "distanceMeters ASC",
    "walkMinutes ASC NULLS LAST",
    "stopId ASC",
]
EXPECTED_ERROR_STATUSES = {"400", "401", "404", "422", "429", "503"}
PROBLEM_FIELDS = {
    "type",
    "title",
    "status",
    "detail",
    "instance",
    "code",
    "traceId",
    "fieldErrors",
}
EXPECTED_SCHEMA_REQUIRED = {
    "PlacesListRequest": set(),
    "PlaceDetailPath": {"placeId"},
    "Location": {"lat", "lng"},
    "DataFreshness": {"provider", "observedAt", "expiresAt", "stale"},
    "PlaceListItem": EXPECTED_LIST_FIELDS,
    "CursorPage": {"size", "hasNext", "nextCursor"},
    "PlacesListResponse": {"items", "page"},
    "SavedPlaceState": {"value", "memo", "tags"},
    "Contact": {"phone", "homepageUrl"},
    "Operations": {
        "operatingHoursText", "closedDaysText", "parkingText", "admissionFeeText"
    },
    "PlaceImage": {"url", "thumbnailUrl", "provider", "observedAt", "expiresAt", "stale"},
    "NearbyStop": set(EXPECTED_NEARBY_FIELDS),
    "PlaceDetailResponse": {
        "placeId", "contentId", "name", "category", "regionCode", "regionLabel",
        "address", "location", "thumbnailUrl", "recommendedStayMinutes",
        "recommendedStaySource", "recommendedStayPolicyVersion",
        "recommendedStayEffectiveAt", "recommendedStayUpdatedAt",
        "operationsSummary", "saved", "overview", "contact", "operations", "images",
        "nearbyStops",
    },
    "FieldError": {"field", "reason"},
    "ProblemDetails": PROBLEM_FIELDS,
}
EXPECTED_SCHEMA_PROPERTIES = {
    **EXPECTED_SCHEMA_REQUIRED,
    "PlacesListRequest": {
        "query", "category", "regionCode", "lat", "lng", "radiusMeters", "cursor", "size", "savedOnly"
    },
}
EXPECTED_SCHEMA_DIGESTS = {
    "PlacesListRequest": "e10118c0d8fe0f6f4da2a210dbb5c9e9aa81e00eff53e5ce9fcd45718f520056",
    "PlaceDetailPath": "2c80be1c2604a34033256df7c54f900caf2e8d11bc80a67827bf8dc4ce44aa22",
    "Location": "5d545fbf900382f1c8259038886baf3925845243a8ac6de18165a25afaabc38a",
    "DataFreshness": "132bfa40d554d4cd63bc3e5ad57af66881f61946c97f4505af5bf022d7832321",
    "PlaceListItem": "d9177a3629b49001b3d0ff8c2966e53ffb61295d9b48362de6174055125840cb",
    "CursorPage": "86db2725e30ba15baae804f4844b8e3aea6650ba0f029a03c5151e20f2b91efb",
    "PlacesListResponse": "a26ed8eb8b7fd70d23df5be2357cbbcfd3d5318342e7714f9854eed20b0a55b7",
    "SavedPlaceState": "fa15ccc8bd4177995c3525ed02a6d08914ac810f984ec57e686bd467874d116b",
    "Contact": "b5a9faac8be0a083d48c3b396c7025815d0ff57effacad77bdb9a2af2981445d",
    "Operations": "cb0d2906830c6b35469cd433e10ad2650fce8e631e7ae8dacfcbfc4916de9340",
    "PlaceImage": "f414a74fac5626c9c8f68e6a4629c6c97980be973e3f017c09c2b69cd25c102e",
    "NearbyStop": "437411b480c46cbe2d8de5ff831932172949ae7d8527a271ece5bea327dd1f75",
    "PlaceDetailResponse": "f81ec8e7637057c3cc87f6f486cd9ded8cd6119776cf1da19800f281ed465f54",
    "FieldError": "5fb09356a2a56dfab89fa6a47fa5eb2498bfb4faa42f567810b4430cd301fddc",
    "ProblemDetails": "c25c20be66d088f93b5b196c0e4a4dd16c3f90593b9045d425a24240a86903ac",
}
EXPECTED_ENDPOINT_PROBLEMS = {
    "/api/v1/places": {
        (400, "INVALID_QUERY_PARAMETER", "https://api.timing-jeju.com/problems/invalid-query-parameter"),
        (400, "INVALID_GEO_FILTER", "https://api.timing-jeju.com/problems/invalid-geo-filter"),
        (400, "CURSOR_CONTEXT_MISMATCH", "https://api.timing-jeju.com/problems/cursor-context-mismatch"),
        (400, "INVALID_CURSOR", "https://api.timing-jeju.com/problems/invalid-cursor"),
        (401, "INVALID_ACCESS_TOKEN", "https://api.timing-jeju.com/problems/invalid-access-token"),
        (422, "PLACE_QUERY_CONSTRAINT_VIOLATION", "https://api.timing-jeju.com/problems/place-query-constraint-violation"),
        (429, "UPSTREAM_RATE_LIMITED", "https://api.timing-jeju.com/problems/upstream-rate-limited"),
        (503, "PLACE_DATA_UNAVAILABLE", "https://api.timing-jeju.com/problems/place-data-unavailable"),
    },
    "/api/v1/places/{placeId}": {
        (400, "INVALID_QUERY_PARAMETER", "https://api.timing-jeju.com/problems/invalid-query-parameter"),
        (401, "INVALID_ACCESS_TOKEN", "https://api.timing-jeju.com/problems/invalid-access-token"),
        (404, "PLACE_NOT_FOUND", "https://api.timing-jeju.com/problems/place-not-found"),
        (503, "PLACE_DATA_UNAVAILABLE", "https://api.timing-jeju.com/problems/place-data-unavailable"),
    },
}
FORBIDDEN_KEYS = {
    "rawtoken",
    "email",
    "apikey",
    "providerpayload",
    "servicerole",
    "jwtsecret",
    "freshnessreason",
}
RFC3339_DATE_TIME = re.compile(
    r"^\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[Zz]|[+-]\d{2}:\d{2})$"
)
RFC3986_URI = re.compile(
    r"^[A-Za-z][A-Za-z0-9+.-]*:[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]*$"
)
INVALID_PERCENT_ESCAPE = re.compile(r"%(?![0-9A-Fa-f]{2})")


class NonStandardJsonConstantError(ValueError):
    """RFC 8259에 없는 Python JSON 숫자 상수를 나타낸다."""


def _reject_json_constant(token: str) -> Any:
    raise NonStandardJsonConstantError(f"비표준 JSON 상수 {token}은 허용하지 않습니다.")


def _expect(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def _find_endpoint(contract: dict[str, Any], path: str) -> dict[str, Any]:
    endpoints = contract.get("endpoints")
    if not isinstance(endpoints, list):
        return {}
    return next(
        (
            endpoint
            for endpoint in endpoints
            if isinstance(endpoint, dict) and endpoint.get("path") == path
        ),
        {},
    )


def _walk_keys(value: Any):
    if isinstance(value, dict):
        for key, child in value.items():
            yield str(key)
            yield from _walk_keys(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_keys(child)


def _is_type(value: Any, expected: str) -> bool:
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    if expected == "string":
        return isinstance(value, str)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return (
            isinstance(value, (int, float))
            and not isinstance(value, bool)
            and (not isinstance(value, float) or math.isfinite(value))
        )
    return False


def _valid_uri(value: str) -> bool:
    if RFC3986_URI.fullmatch(value) is None or INVALID_PERCENT_ESCAPE.search(value):
        return False
    try:
        parsed = urlsplit(value)
        _ = parsed.port
    except ValueError:
        return False
    if parsed.scheme.lower() in {"http", "https"}:
        return bool(parsed.netloc and parsed.hostname)
    return bool(parsed.path)


def _strict_json_loads(text: str) -> Any:
    return json.loads(text, parse_constant=_reject_json_constant)


def _parse_rfc3339(value: str) -> datetime | None:
    if RFC3339_DATE_TIME.fullmatch(value) is None:
        return None
    try:
        normalized = value.replace("t", "T")
        if normalized[-1] in {"Z", "z"}:
            normalized = normalized[:-1] + "+00:00"
        parsed = datetime.fromisoformat(normalized)
    except (ValueError, AttributeError):
        return None
    return parsed if parsed.tzinfo is not None else None


def _valid_format(value: str, format_name: str) -> bool:
    if format_name == "uuid":
        try:
            return str(UUID(value)) == value
        except (ValueError, AttributeError):
            return False
    if format_name == "date-time":
        return _parse_rfc3339(value) is not None
    if format_name == "uri":
        return _valid_uri(value)
    if format_name == "urn":
        return value.lower().startswith("urn:") and _valid_uri(value)
    if format_name == "trace-id":
        return re.fullmatch(r"[0-9a-f]{32}", value) is not None
    return False


def _validate_value(
    value: Any,
    schema: Any,
    schemas: dict[str, Any],
    path: str,
    errors: list[str],
) -> None:
    if not isinstance(schema, dict):
        errors.append(f"{path}: schema가 객체가 아닙니다.")
        return
    if value is None:
        if schema.get("nullable") is not True:
            errors.append(f"{path}: null을 허용하지 않습니다.")
        return
    reference = schema.get("$ref")
    if reference is not None:
        referenced = schemas.get(reference)
        if not isinstance(referenced, dict):
            errors.append(f"{path}: 알 수 없는 schema reference {reference!r}입니다.")
            return
        _validate_value(value, referenced, schemas, path, errors)
        return
    expected_type = schema.get("type")
    if not isinstance(expected_type, str) or not _is_type(value, expected_type):
        errors.append(f"{path}: {expected_type} 타입이어야 합니다.")
        return
    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path}: 허용 enum {schema['enum']!r}에 포함되지 않습니다.")
    if expected_type in {"integer", "number"}:
        if "minimum" in schema and value < schema["minimum"]:
            errors.append(f"{path}: minimum {schema['minimum']}보다 작습니다.")
        if "maximum" in schema and value > schema["maximum"]:
            errors.append(f"{path}: maximum {schema['maximum']}보다 큽니다.")
    if expected_type == "string":
        normalized_value = value.strip() if schema.get("normalization") == "trim" else value
        if "minLength" in schema and len(normalized_value) < schema["minLength"]:
            errors.append(f"{path}: minLength {schema['minLength']}를 충족하지 않습니다.")
        if "maxLength" in schema and len(normalized_value) > schema["maxLength"]:
            errors.append(f"{path}: maxLength {schema['maxLength']}를 초과합니다.")
        if "pattern" in schema and re.fullmatch(schema["pattern"], normalized_value) is None:
            errors.append(f"{path}: pattern {schema['pattern']!r}과 일치하지 않습니다.")
        if "format" in schema and not _valid_format(normalized_value, schema["format"]):
            errors.append(f"{path}: {schema['format']} format이 아닙니다.")
    if expected_type == "object":
        properties = schema.get("properties")
        required = schema.get("required")
        if not isinstance(properties, dict) or not isinstance(required, list):
            errors.append(f"{path}: object properties/required 계약이 없습니다.")
            return
        missing = sorted(set(required) - set(value))
        if missing:
            errors.append(f"{path}: 필수 필드 {', '.join(missing)}가 없습니다.")
        if schema.get("additionalProperties") is False:
            extras = sorted(set(value) - set(properties))
            if extras:
                errors.append(f"{path}: 정의되지 않은 필드 {', '.join(extras)}가 있습니다.")
        for key, child in value.items():
            if key in properties:
                _validate_value(child, properties[key], schemas, f"{path}.{key}", errors)
        dependencies = schema.get("dependentRequired", {})
        if isinstance(dependencies, dict):
            for key, dependent_keys in dependencies.items():
                if key in value:
                    missing_dependencies = [item for item in dependent_keys if item not in value]
                    if missing_dependencies:
                        dependency_label = (
                            "lat/lng 조합"
                            if key in {"lat", "lng", "radiusMeters"}
                            else key
                        )
                        errors.append(
                            f"{path}: {dependency_label}에는 {', '.join(missing_dependencies)}가 함께 필요합니다."
                        )
    if expected_type == "array":
        if "minItems" in schema and len(value) < schema["minItems"]:
            errors.append(f"{path}: minItems {schema['minItems']}를 충족하지 않습니다.")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            errors.append(f"{path}: maxItems {schema['maxItems']}를 초과합니다.")
        if schema.get("uniqueItems") is True:
            serialized = [
                json.dumps(item, ensure_ascii=False, sort_keys=True, default=repr)
                for item in value
            ]
            if len(serialized) != len(set(serialized)):
                errors.append(f"{path}: 배열 값이 중복됩니다.")
        unique_by = schema.get("uniqueBy")
        if isinstance(unique_by, str):
            keys = [item.get(unique_by) for item in value if isinstance(item, dict)]
            if len(keys) != len(value) or len(keys) != len(set(keys)):
                errors.append(f"{path}: {unique_by} 값이 누락되거나 중복됩니다.")
        item_schema = schema.get("items")
        if not isinstance(item_schema, dict):
            errors.append(f"{path}: array items schema가 없습니다.")
            return
        for index, item in enumerate(value):
            _validate_value(item, item_schema, schemas, f"{path}[{index}]", errors)


def _validate_schemas(contract: dict[str, Any], errors: list[str]) -> None:
    schemas = contract.get("schemas")
    if not isinstance(schemas, dict) or set(schemas) != set(EXPECTED_SCHEMA_REQUIRED):
        errors.append("request/success/problem의 닫힌 schema 목록이 다릅니다.")
        return
    for name, expected_required in EXPECTED_SCHEMA_REQUIRED.items():
        schema = schemas.get(name)
        if not isinstance(schema, dict):
            errors.append(f"{name}: schema가 없습니다.")
            continue
        properties = schema.get("properties")
        _expect(
            schema.get("type") == "object"
            and schema.get("additionalProperties") is False
            and isinstance(properties, dict)
            and set(schema.get("required", [])) == expected_required
            and set(properties or {}) == EXPECTED_SCHEMA_PROPERTIES[name],
            f"{name}: required/optional/additionalProperties 닫힌 구조가 다릅니다.",
            errors,
        )
        for property_name, property_schema in (properties or {}).items():
            _expect(
                isinstance(property_schema, dict)
                and property_schema.get("nullable") in {True, False}
                and ("type" in property_schema or "$ref" in property_schema),
                f"{name}.{property_name}: type/ref와 nullability가 명시돼야 합니다.",
                errors,
            )
        digest = hashlib.sha256(
            json.dumps(schema, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        _expect(
            digest == EXPECTED_SCHEMA_DIGESTS[name],
            f"{name}: canonical type/ref/nullability/format/range/pattern/enum/array/dependency 제약이 다릅니다.",
            errors,
        )


def _validate_temporal_order(
    value: Any, path: str, errors: list[str], enforce_order: bool = True
) -> None:
    if isinstance(value, dict):
        observed_at = value.get("observedAt")
        expires_at = value.get("expiresAt")
        if enforce_order and isinstance(observed_at, str) and isinstance(expires_at, str):
            observed = _parse_rfc3339(observed_at)
            expires = _parse_rfc3339(expires_at)
            if observed is None or expires is None:
                errors.append(
                    f"{path}: observedAt/expiresAt은 timezone을 포함한 RFC 3339여야 합니다."
                )
            elif observed > expires:
                errors.append(f"{path}: observedAt은 expiresAt보다 늦을 수 없습니다.")
        for key, child in value.items():
            _validate_temporal_order(
                child, f"{path}.{key}", errors, enforce_order=key != "nearbyStops"
            )
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _validate_temporal_order(child, f"{path}[{index}]", errors, enforce_order)


def _endpoint_problem_tuples(endpoint: dict[str, Any]) -> set[tuple[int, str, str]]:
    problems = endpoint.get("problems")
    if not isinstance(problems, list):
        return set()
    return {
        (problem.get("status"), problem.get("code"), problem.get("type"))
        for problem in problems
        if isinstance(problem, dict) and problem.get("condition")
    }


def _validate_identity(contract: dict[str, Any], errors: list[str]) -> None:
    _expect(
        contract.get("schemaVersion") == "timing-jeju-places-contract/v1",
        "장소 계약 schemaVersion이 다릅니다.",
        errors,
    )
    _expect(
        contract.get("contractVersion") == "1.0.0"
        and contract.get("sourceSpecVersion") == "v1.1",
        "local/Notion source spec와 #72 contract version mapping이 다릅니다.",
        errors,
    )
    _expect(
        contract.get("inherits") == "timing-jeju-rest-contract/v1",
        "장소 계약은 #72 공통 계약을 상속해야 합니다.",
        errors,
    )
    _expect(
        contract.get("ownerIssue") == 83 and contract.get("implementationIssue") == 66,
        "장소 contract owner는 #83, 구현 owner는 #66이어야 합니다.",
        errors,
    )

    endpoints = contract.get("endpoints")
    identities = (
        {
            (endpoint.get("method"), endpoint.get("path"))
            for endpoint in endpoints
            if isinstance(endpoint, dict)
        }
        if isinstance(endpoints, list)
        else set()
    )
    _expect(
        isinstance(endpoints, list)
        and len(endpoints) == 2
        and identities == EXPECTED_ENDPOINTS,
        "장소 계약은 정확한 두 endpoint method/path만 가져야 합니다.",
        errors,
    )
    for endpoint in endpoints if isinstance(endpoints, list) else []:
        if not isinstance(endpoint, dict):
            continue
        auth = endpoint.get("auth")
        _expect(
            auth
            == {"mode": "optional", "missingToken": "anonymous", "invalidToken": 401},
            "두 장소 endpoint는 #72 Optional 인증 계약을 사용해야 합니다.",
            errors,
        )
        _expect(
            endpoint.get("successStatus") == 200,
            "장소 조회 성공 status는 200이어야 합니다.",
            errors,
        )
        expected_schemas = (
            ("PlacesListRequest", "PlacesListResponse")
            if endpoint.get("path") == "/api/v1/places"
            else ("PlaceDetailPath", "PlaceDetailResponse")
        )
        _expect(
            (endpoint.get("requestSchema"), endpoint.get("successSchema"))
            == expected_schemas,
            "endpoint별 request/success schema 연결이 다릅니다.",
            errors,
        )
        _expect(
            _endpoint_problem_tuples(endpoint)
            == EXPECTED_ENDPOINT_PROBLEMS.get(endpoint.get("path"), set())
            and all(
                isinstance(problem, dict)
                and set(problem) == {"condition", "status", "code", "type"}
                and bool(problem.get("condition"))
                for problem in endpoint.get("problems", [])
            ),
            "endpoint별 오류 condition/status/code/type matrix가 다릅니다.",
            errors,
        )


def _validate_list_query(contract: dict[str, Any], errors: list[str]) -> None:
    endpoint = _find_endpoint(contract, "/api/v1/places")
    query = endpoint.get("query", {})
    _expect(
        query.get("query")
        == {
            "required": False,
            "nullable": False,
            "omitted": "전체 이름/별칭",
            "type": "trimmed string",
            "minimumLength": 1,
            "maximumLength": 100,
        },
        "query는 trim 후 1~100자인 문자열이어야 합니다.",
        errors,
    )
    geo = (
        query.get("lat", {}),
        query.get("lng", {}),
        query.get("radiusMeters", {}),
    )
    valid_geo = (
        geo[0].get("minimum") == 33
        and geo[0].get("maximum") == 34
        and geo[0].get("pairedWith") == "lng"
        and geo[1].get("minimum") == 126
        and geo[1].get("maximum") == 127
        and geo[1].get("pairedWith") == "lat"
        and geo[2].get("minimum") == 100
        and geo[2].get("maximum") == 50000
        and geo[2].get("default") == 10000
        and geo[2].get("requires") == ["lat", "lng"]
    )
    _expect(valid_geo, "lat/lng/radiusMeters 범위와 조합 계약이 다릅니다.", errors)

    size = query.get("size", {})
    cursor_query = query.get("cursor", {})
    saved_only = query.get("savedOnly", {})
    _expect(
        size.get("minimum") == 1
        and size.get("maximum") == 100
        and size.get("default") == 20
        and cursor_query.get("type") == "opaque string"
        and saved_only
        == {"required": False, "nullable": False, "type": "boolean", "default": False},
        "cursor/size query 계약이 다릅니다.",
        errors,
    )
    pagination = endpoint.get("pagination", {})
    expected_scope = [
        "query",
        "category",
        "regionCode",
        "lat",
        "lng",
        "radiusMeters",
        "size",
        "savedOnly",
        "sortProfile",
    ]
    _expect(
        pagination.get("type") == "cursor"
        and pagination.get("cursor") == "opaque"
        and pagination.get("size") == {"default": 20, "max": 100}
        and pagination.get("tieBreaker") == "placeId ASC",
        "cursor stable sort/tie-breaker 계약이 다릅니다.",
        errors,
    )
    _expect(
        pagination.get("filterChange") == "reject CURSOR_CONTEXT_MISMATCH"
        and pagination.get("cursorScope") == expected_scope,
        "필터 변경 시 cursor를 거부해야 합니다.",
        errors,
    )


def _validate_response(contract: dict[str, Any], errors: list[str]) -> None:
    _expect(
        contract.get("anonymousPersonalization")
        == {
            "list": {"saved": False, "memo": None, "tags": []},
            "detail": {"saved": {"value": False, "memo": None, "tags": []}},
        },
        "Optional 인증의 익명 saved/memo/tags shape가 다릅니다.",
        errors,
    )
    response = contract.get("responseConsistency", {})
    list_fields = response.get("list", [])
    detail_fields = response.get("detail", [])
    _expect(
        isinstance(list_fields, list)
        and isinstance(detail_fields, list)
        and set(list_fields) == EXPECTED_LIST_FIELDS
        and EXPECTED_DETAIL_SHARED_FIELDS.issubset(set(detail_fields))
        and {"images", "operations", "nearbyStops"}.issubset(set(detail_fields))
        and set(response.get("rules", {}))
        == {"recommendedStayMinutes", "recommendedStayProvenance", "thumbnailUrl", "operationsSummary", "dataFreshness"},
        "recommendedStayMinutes·이미지·운영정보의 목록/상세 일관성 계약이 다릅니다.",
        errors,
    )

    ownership = contract.get("fieldOwnership")
    expected_groups = {
        "placeCore",
        "recommendedStayMinutes",
        "images",
        "operations",
        "savedMemoTags",
        "nearbyStops",
    }
    _expect(
        isinstance(ownership, dict) and set(ownership) == expected_groups,
        "TourAPI/TAGO/앱 큐레이션 필드 owner mapping이 다릅니다.",
        errors,
    )
    for group, entry in ownership.items() if isinstance(ownership, dict) else []:
        _expect(
            isinstance(entry, dict) and set(entry) == PROVENANCE_FIELDS,
            f"{group}의 provider/observedAt/expiresAt/stale 계약이 필요합니다.",
            errors,
        )
    _expect(
        ownership.get("recommendedStayMinutes", {}).get("owner")
        == "Timing Jeju app curation"
        and ownership.get("nearbyStops", {}).get("provider")
        == "place_stop_links.source_provider",
        "필드 owner와 provider projection이 다릅니다.",
        errors,
    )


def _validate_nearby_stops(contract: dict[str, Any], errors: list[str]) -> None:
    nearby = contract.get("nearbyStops", {})
    _expect(
        nearby.get("presence") == "required non-null array from #66 contract version"
        and nearby.get("itemFields") == EXPECTED_NEARBY_FIELDS
        and nearby.get("nullableFields") == ["walkMinutes"],
        "nearbyStops itemFields와 null 아닌 배열 계약이 다릅니다.",
        errors,
    )
    _expect(
        nearby.get("effectiveExpiresAt")
        == "least(place_stop_links.expires_at, non-null bus_stops.stale_at)",
        "nearbyStops effective expiresAt projection이 다릅니다.",
        errors,
    )
    _expect(
        nearby.get("distancePolicy")
        == {
            "property": "app.places.nearby-stops.max-distance-meters",
            "default": 500,
            "minimum": 1,
            "maximum": 500,
            "inclusive": True,
        },
        "nearbyStops validated distance config가 다릅니다.",
        errors,
    )
    _expect(
        nearby.get("eligibility") == EXPECTED_ELIGIBILITY,
        "disabled/tombstoned/out-of-radius/bus stop lifecycle 제외 계약이 다릅니다.",
        errors,
    )
    inclusion = nearby.get("inclusion", {})
    _expect(
        inclusion
        == {
            "fresh": True,
            "staleOnly": True,
            "disabled": False,
            "tombstoned": False,
            "outOfRadius": False,
            "stopTombstoned": False,
            "stopSourceDeleted": False,
        },
        "stale-only 포함과 비eligible 제외 계약이 다릅니다.",
        errors,
    )
    _expect(
        nearby.get("emptyWhen") == "eligible fresh/stale link count = 0"
        and nearby.get("emptyResponse") == {"status": 200, "nearbyStops": []},
        "eligible 없음은 200 + nearbyStops 빈 배열이어야 합니다.",
        errors,
    )
    _expect(
        nearby.get("sort") == EXPECTED_SORT,
        "nearbyStops 정렬은 stale/distance/walkMinutes NULLS LAST/stopId 순이어야 합니다.",
        errors,
    )
    _expect(nearby.get("maxItems") == 5, "nearbyStops는 전체 최대 5개여야 합니다.", errors)
    _expect(
        nearby.get("deduplicateBy") == "stopId",
        "nearbyStops stopId 중복은 한 번만 반환해야 합니다.",
        errors,
    )
    _expect(
        nearby.get("freshBoundary") == "effective expiresAt > now()"
        and nearby.get("staleBoundary") == "effective expiresAt <= now()",
        "expiresAt 직전/동일/직후 freshness 경계가 다릅니다.",
        errors,
    )
    _expect(
        nearby.get("owners")
        == {"contractIssue": 83, "migrationIssue": 37, "implementationIssue": 66},
        "nearbyStops #37/#66/#83 owner mapping이 다릅니다.",
        errors,
    )
    readiness = nearby.get("readiness", {})
    _expect(
        readiness
        == {
            "metadata": "not-ready",
            "example": "not-ready",
            "implementation": "not-ready",
        },
        "외부 연결과 #66 증거 전 nearbyStops readiness는 모두 not-ready여야 합니다.",
        errors,
    )


def _validate_errors(contract: dict[str, Any], errors: list[str]) -> None:
    endpoints = contract.get("endpoints", [])
    matrix = {
        str(status): sorted(
            {
                code
                for endpoint in endpoints
                if isinstance(endpoint, dict)
                for status_value, code, _ in _endpoint_problem_tuples(endpoint)
                if status_value == status
            }
        )
        for status in (400, 401, 404, 422, 429, 503)
    }
    _expect(
        set(matrix) == EXPECTED_ERROR_STATUSES
        and all(
            isinstance(codes, list)
            and codes
            and all(isinstance(code, str) and code for code in codes)
            for codes in matrix.values()
        ),
        "endpoint별 오류 condition/status/code/type matrix가 완전하지 않습니다.",
        errors,
    )
    example = contract.get("problemExample")
    _expect(
        isinstance(example, dict)
        and set(example) == PROBLEM_FIELDS
        and example.get("status") == 400
        and example.get("code") == "INVALID_GEO_FILTER"
        and any("가" <= char <= "힣" for char in str(example.get("title", "")))
        and any("가" <= char <= "힣" for char in str(example.get("detail", "")))
        and len(str(example.get("traceId", ""))) == 32,
        "한국어 Problem Details 예시와 code/detail/traceId 계약이 다릅니다.",
        errors,
    )


def _validate_traceability(
    contract: dict[str, Any], errors: list[str], repo_root: Path
) -> None:
    traceability = contract.get("traceability", {})
    local_document = traceability.get("localDocument")
    _expect(
        local_document == "docs/contracts/domains/places/contract.md"
        and (repo_root / str(local_document)).is_file(),
        "로컬 places 계약 문서 evidence가 없습니다.",
        errors,
    )
    notion = traceability.get("notion", {})
    notion_endpoints = notion.get("endpoints", [])
    notion_identities = {
        (entry.get("method"), entry.get("path"))
        for entry in notion_endpoints
        if isinstance(entry, dict)
    }
    _expect(
        notion.get("databaseId") == "40914d1e-551f-4cfc-9604-0190ecda7b6c"
        and notion.get("declaredVersion") == contract.get("sourceSpecVersion")
        and len(notion_endpoints) == 2
        and notion_identities == EXPECTED_ENDPOINTS
        and all(entry.get("specStatus") == "Draft" for entry in notion_endpoints)
        and next(
            (
                set(entry.get("canonicalListItemFields", []))
                for entry in notion_endpoints
                if entry.get("path") == "/api/v1/places"
            ),
            set(),
        )
        == EXPECTED_LIST_FIELDS
        and all(
            str(entry.get("url", "")).startswith("https://app.notion.com/p/")
            and str(entry.get("pageId", "")).replace("-", "")
            in str(entry.get("url", ""))
            for entry in notion_endpoints
        ),
        "Notion 두 endpoint Draft 상태와 local canonical field/version 추적성이 다릅니다.",
        errors,
    )
    figma = traceability.get("figma", {})
    screens = figma.get("screens", [])
    _expect(
        figma.get("fileKey") == "4mKep38zm17iupVSQVsSJW"
        and figma.get("nodeId") == "251-4347"
        and "node-id=251-4347" in str(figma.get("url", ""))
        and len(screens) == 2
        and {entry.get("endpoint") for entry in screens if isinstance(entry, dict)}
        == {f"{method} {path}" for method, path in EXPECTED_ENDPOINTS}
        and all(
            all(entry.get(field) for field in ("action", "loading", "empty", "error"))
            for entry in screens
            if isinstance(entry, dict)
        ),
        "Figma node/action/loading/empty/error 추적성이 다릅니다.",
        errors,
    )


def _validate_catalog_alignment(errors: list[str], repo_root: Path) -> None:
    catalog_path = repo_root / "docs/contracts/rest/catalog.json"
    try:
        catalog = _strict_json_loads(catalog_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, NonStandardJsonConstantError) as error:
        errors.append(
            "REST catalog를 읽을 수 없어 local/Notion readiness를 비교할 수 없습니다: "
            f"{error}"
        )
        return
    endpoints = catalog.get("endpoints", []) if isinstance(catalog, dict) else []
    places_endpoints = {
        entry.get("path"): entry
        for entry in endpoints
        if isinstance(entry, dict) and entry.get("path") in {path for _, path in EXPECTED_ENDPOINTS}
    }
    list_entry = places_endpoints.get("/api/v1/places", {})
    detail_entry = places_endpoints.get("/api/v1/places/{placeId}", {})
    _expect(
        set(places_endpoints) == {path for _, path in EXPECTED_ENDPOINTS}
        and list_entry.get("schemas", {}).get("query") == "PlacesListRequest"
        and detail_entry.get("schemas", {}).get("path") == "PlaceDetailPath"
        and "dataFreshness={provider,observedAt,expiresAt,stale} required"
        in str(list_entry.get("presence", "")),
        "REST catalog와 places request/dataFreshness schema가 다릅니다.",
        errors,
    )
    domain_contracts = catalog.get("domainContracts", []) if isinstance(catalog, dict) else []
    places_domain = next(
        (
            entry
            for entry in domain_contracts
            if isinstance(entry, dict) and entry.get("issue") == 83
        ),
        {},
    )
    readiness = places_domain.get("readiness", {})
    metadata = readiness.get("metadata", {})
    _expect(
        places_domain.get("versions")
        == {"local": "1.0.0", "notion": "not-linked", "figma": "not-linked"}
        and metadata == {"status": "not-ready", "evidence": None}
        and readiness.get("example") == {"status": "not-ready", "evidence": None}
        and readiness.get("implementation") == {"status": "not-ready", "evidence": None},
        "external version 미정렬 시 metadata/example/implementation은 모두 not-ready여야 합니다.",
        errors,
    )
    document_path = repo_root / "docs/contracts/domains/places/contract.md"
    try:
        document = document_path.read_text(encoding="utf-8")
    except OSError:
        document = ""
    readiness_marker = (
        "readiness: metadata=not-ready, example=not-ready, implementation=not-ready"
    )
    _expect(
        readiness_marker in document,
        "catalog의 not-ready 상태와 readiness 문서가 일치하지 않습니다.",
        errors,
    )


def _validate_api_spec_alignment(errors: list[str], repo_root: Path) -> None:
    api_spec_path = repo_root / "docs/designs/timing-jeju-backend-rdb-api-spec.md"
    try:
        api_spec = api_spec_path.read_text(encoding="utf-8")
    except OSError:
        errors.append("장소 상세 operations를 비교할 Spring REST API 명세가 없습니다.")
        return
    operations_match = re.search(
        r'"operations"\s*:\s*\{(?P<body>.*?)\n\s*\},\s*\n\s*"images"',
        api_spec,
        re.DOTALL,
    )
    operation_fields = (
        set(re.findall(r'^\s*"([^"]+)"\s*:', operations_match.group("body"), re.MULTILINE))
        if operations_match is not None
        else set()
    )
    _expect(
        operation_fields
        == {"operatingHoursText", "closedDaysText", "parkingText", "admissionFeeText"},
        "Spring REST API 명세의 operations 4개 필드가 canonical schema와 다릅니다.",
        errors,
    )


def _validate_fixtures(
    contract: dict[str, Any], errors: list[str], repo_root: Path
) -> None:
    fixtures = contract.get("fixtures")
    if not isinstance(fixtures, dict) or set(fixtures) != {"request", "success", "problem"}:
        errors.append("request/success/problem fixture linkage가 필요합니다.")
        return
    loaded: dict[str, Any] = {}
    for kind, relative_value in fixtures.items():
        relative = Path(str(relative_value))
        expected_prefix = Path("fixtures/contracts/places")
        if (
            relative.is_absolute()
            or not relative.is_relative_to(expected_prefix)
            or relative.suffix != ".json"
        ):
            errors.append(f"{kind} fixture는 places 범위의 JSON이어야 합니다.")
            continue
        try:
            loaded[kind] = _strict_json_loads(
                (repo_root / relative).read_text(encoding="utf-8")
            )
        except (OSError, json.JSONDecodeError, NonStandardJsonConstantError) as error:
            errors.append(f"{kind} fixture가 없거나 올바른 JSON이 아닙니다: {error}")
    if set(loaded) != {"request", "success", "problem"}:
        return
    schemas = contract.get("schemas")
    if not isinstance(schemas, dict):
        errors.append("fixture를 검사할 닫힌 schema가 없습니다.")
        return
    request = loaded["request"]
    request_endpoints = request.get("endpoints", {}) if isinstance(request, dict) else {}
    _expect(
        isinstance(request, dict)
        and set(request) == {"contractVersion", "sourceSpecVersion", "endpoints"}
        and request.get("contractVersion") == contract.get("contractVersion")
        and request.get("sourceSpecVersion") == contract.get("sourceSpecVersion")
        and isinstance(request_endpoints, dict)
        and set(request_endpoints) == {"list", "detail"},
        "request fixture는 버전과 목록/상세 두 endpoint의 닫힌 구조를 포함해야 합니다.",
        errors,
    )
    list_request = request_endpoints.get("list", {})
    detail_request = request_endpoints.get("detail", {})
    _expect(
        isinstance(list_request, dict)
        and set(list_request) == {"method", "path", "query"}
        and list_request.get("method") == "GET"
        and list_request.get("path") == "/api/v1/places",
        "목록 request fixture method/path/query 구조가 다릅니다.",
        errors,
    )
    _validate_value(
        list_request.get("query"),
        schemas.get("PlacesListRequest"),
        schemas,
        "request.list.query",
        errors,
    )
    _expect(
        isinstance(detail_request, dict)
        and set(detail_request) == {"method", "path", "pathParameters"}
        and detail_request.get("method") == "GET"
        and detail_request.get("path")
        == "/api/v1/places/20000000-0000-0000-0000-000000000002",
        "상세 request fixture method/path/pathParameters 구조가 다릅니다.",
        errors,
    )
    _validate_value(
        detail_request.get("pathParameters"),
        schemas.get("PlaceDetailPath"),
        schemas,
        "request.detail.pathParameters",
        errors,
    )
    success = loaded["success"]
    _expect(
        isinstance(success, dict) and set(success) == {"list", "detail"},
        "success fixture는 목록/상세 응답만 가져야 합니다.",
        errors,
    )
    _validate_value(
        success.get("list"),
        schemas.get("PlacesListResponse"),
        schemas,
        "success.list",
        errors,
    )
    _validate_value(
        success.get("detail"),
        schemas.get("PlaceDetailResponse"),
        schemas,
        "success.detail",
        errors,
    )
    _validate_temporal_order(success, "success", errors)
    problem_fixture = loaded["problem"]
    _expect(
        isinstance(problem_fixture, dict)
        and set(problem_fixture) == EXPECTED_ERROR_STATUSES,
        "problem fixture가 오류 matrix와 Problem Details shape를 모두 포함해야 합니다.",
        errors,
    )
    allowed_problems = {
        problem
        for endpoint_problems in EXPECTED_ENDPOINT_PROBLEMS.values()
        for problem in endpoint_problems
    }
    for status_key, problem in (
        problem_fixture.items() if isinstance(problem_fixture, dict) else []
    ):
        _validate_value(
            problem,
            schemas.get("ProblemDetails"),
            schemas,
            f"problem.{status_key}",
            errors,
        )
        if not isinstance(problem, dict):
            continue
        actual = (problem.get("status"), problem.get("code"), problem.get("type"))
        allowed_for_status = sorted(
            code for status, code, _ in allowed_problems if str(status) == status_key
        )
        _expect(
            str(problem.get("status")) == status_key and actual in allowed_problems,
            f"problem.{status_key}: endpoint matrix의 {allowed_for_status} status/code/type 대응이어야 합니다.",
            errors,
        )


def validate_contract(contract: Any, repo_root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    if not isinstance(contract, dict):
        return ["places 계약은 JSON 객체여야 합니다."]
    forbidden = sorted(
        key for key in _walk_keys(contract) if key.replace("_", "").lower() in FORBIDDEN_KEYS
    )
    if forbidden:
        errors.append(f"민감정보 또는 금지 필드가 계약에 있습니다: {', '.join(forbidden)}")
    _validate_schemas(contract, errors)
    _validate_identity(contract, errors)
    _validate_list_query(contract, errors)
    _validate_response(contract, errors)
    _validate_nearby_stops(contract, errors)
    _validate_errors(contract, errors)
    _validate_traceability(contract, errors, repo_root)
    _validate_catalog_alignment(errors, repo_root)
    _validate_api_spec_alignment(errors, repo_root)
    _validate_fixtures(contract, errors, repo_root)
    return errors


def load_contract(path: Path) -> Any:
    return _strict_json_loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Issue #83 장소 REST 계약 검사")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        contract = load_contract(args.contract)
    except (OSError, json.JSONDecodeError, NonStandardJsonConstantError) as error:
        print(f"장소 REST 계약 검사 실패: {error}", file=sys.stderr)
        return 1
    errors = validate_contract(contract, args.root.resolve())
    if errors:
        print("장소 REST 계약 검사 실패:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"장소 REST 계약 검사 성공: {args.contract}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
