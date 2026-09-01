#!/usr/bin/env python3
"""Issue #87 복수 숙소 CRUD canonical 계약을 fail-closed로 검사한다."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import date
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.validate_preferences_transport_contract import (  # noqa: E402
    _validate_schema_value as _validate_value,
)

DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/accommodations/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
FIXTURES = ROOT / "fixtures/contracts/accommodations"
EXPECTED_ENDPOINTS = {
    ("POST", "/api/v1/trips/{tripId}/accommodations"),
    ("PATCH", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"),
    ("DELETE", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"),
}
ENDPOINT_FIELDS = {
    "method", "path", "operation", "requestSchema", "headersSchema", "successSchema",
    "auth", "owner", "presence", "responses", "errorMatrix", "idempotency", "pagination",
    "concurrency", "dbOwner", "requestTimeCall", "dataLineage", "figma", "contractVersion",
}
TOP_FIELDS = {
    "schemaVersion", "contractVersion", "sourceSpecVersion", "inherits", "ownerIssue",
    "implementationIssues", "schemas", "endpoints", "accommodationPolicy", "patchPolicy",
    "activeSchedulePolicy", "errorConditions", "externalTraceability", "readiness", "schemaGap",
}
SCHEMA_NAMES = {
    "TripPath", "AccommodationPath", "CreateHeaders", "MutationHeaders",
    "CreateAccommodationRequest", "PatchAccommodationRequest", "Accommodation",
    "AccommodationMutationResponse",
}
EXPECTED_SCHEMA_PROPERTIES = {
    "TripPath": {
        "tripId": {"type": "string", "nullable": False, "format": "uuid"},
    },
    "AccommodationPath": {
        "tripId": {"type": "string", "nullable": False, "format": "uuid"},
        "accommodationId": {"type": "string", "nullable": False, "format": "uuid"},
    },
    "CreateHeaders": {
        "Authorization": {"type": "string", "nullable": False, "pattern": r"^Bearer [^\s]{1,2048}$"},
        "Idempotency-Key": {"type": "string", "nullable": False, "minLength": 1, "maxLength": 128, "pattern": "^[!-~]+$"},
        "If-Match": {"type": "string", "nullable": False, "pattern": r'^\"trip-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-r[1-9][0-9]*\"$'},
    },
    "MutationHeaders": {
        "Authorization": {"type": "string", "nullable": False, "pattern": r"^Bearer [^\s]{1,2048}$"},
        "If-Match": {"type": "string", "nullable": False, "pattern": r'^\"trip-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-r[1-9][0-9]*\"$'},
    },
    "CreateAccommodationRequest": {
        "placeId": {"type": "string", "nullable": True, "format": "uuid"},
        "customName": {"type": "string", "nullable": True, "minLength": 1, "maxLength": 100, "normalization": "trim+nfc"},
        "checkInDate": {"type": "string", "nullable": False, "format": "date", "pattern": r"^\d{4}-\d{2}-\d{2}$"},
        "checkOutDate": {"type": "string", "nullable": False, "format": "date", "pattern": r"^\d{4}-\d{2}-\d{2}$"},
        "checkInTime": {"type": "string", "nullable": False, "format": "time", "pattern": r"^(?:[01]\d|2[0-3]):[0-5]\d$"},
        "checkOutTime": {"type": "string", "nullable": False, "format": "time", "pattern": r"^(?:[01]\d|2[0-3]):[0-5]\d$"},
    },
    "PatchAccommodationRequest": {
        "placeId": {"type": "string", "nullable": True, "format": "uuid"},
        "customName": {"type": "string", "nullable": True, "minLength": 1, "maxLength": 100, "normalization": "trim+nfc"},
        "checkInDate": {"type": "string", "nullable": False, "format": "date", "pattern": r"^\d{4}-\d{2}-\d{2}$"},
        "checkOutDate": {"type": "string", "nullable": False, "format": "date", "pattern": r"^\d{4}-\d{2}-\d{2}$"},
        "checkInTime": {"type": "string", "nullable": False, "format": "time", "pattern": r"^(?:[01]\d|2[0-3]):[0-5]\d$"},
        "checkOutTime": {"type": "string", "nullable": False, "format": "time", "pattern": r"^(?:[01]\d|2[0-3]):[0-5]\d$"},
    },
    "Accommodation": {
        "accommodationId": {"type": "string", "nullable": False, "format": "uuid"},
        "placeId": {"type": "string", "nullable": True, "format": "uuid"},
        "customName": {"type": "string", "nullable": True, "minLength": 1, "maxLength": 100, "normalization": "trim+nfc"},
        "name": {"type": "string", "nullable": False, "minLength": 1, "maxLength": 100},
        "checkInDate": {"type": "string", "nullable": False, "format": "date", "pattern": r"^\d{4}-\d{2}-\d{2}$"},
        "checkOutDate": {"type": "string", "nullable": False, "format": "date", "pattern": r"^\d{4}-\d{2}-\d{2}$"},
        "checkInTime": {"type": "string", "nullable": False, "format": "time", "pattern": r"^(?:[01]\d|2[0-3]):[0-5]\d$"},
        "checkOutTime": {"type": "string", "nullable": False, "format": "time", "pattern": r"^(?:[01]\d|2[0-3]):[0-5]\d$"},
        "sequenceNo": {"type": "integer", "nullable": False, "minimum": 1},
    },
    "AccommodationMutationResponse": {
        "tripId": {"type": "string", "nullable": False, "format": "uuid"},
        "accommodationId": {"type": "string", "nullable": False, "format": "uuid"},
        "accommodation": {"$ref": "Accommodation"},
        "scheduleEffect": {"type": "string", "nullable": False, "enum": ["none", "invalidated"]},
        "regenerationRequired": {"type": "boolean", "nullable": False},
        "activeScheduleVersionId": {"type": "string", "nullable": True, "format": "uuid"},
        "tripStatus": {"type": "string", "nullable": False, "enum": ["draft", "planned"]},
        "etag": {"type": "string", "nullable": False, "pattern": r'^\"trip-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-r[1-9][0-9]*\"$'},
        "createdAt": {"type": "string", "nullable": False, "format": "date-time", "offset": "+09:00"},
        "updatedAt": {"type": "string", "nullable": False, "format": "date-time", "offset": "+09:00"},
    },
}
EXPECTED_ENDPOINT_SCHEMAS = {
    ("POST", "/api/v1/trips/{tripId}/accommodations"): {
        "operation": "create", "requestSchema": "TripPath + CreateAccommodationRequest",
        "headersSchema": {"schema": "CreateHeaders", "required": ["Authorization", "Idempotency-Key", "If-Match"]},
        "successSchema": "AccommodationMutationResponse", "successStatuses": [201],
    },
    ("PATCH", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"): {
        "operation": "update", "requestSchema": "AccommodationPath + PatchAccommodationRequest",
        "headersSchema": {"schema": "MutationHeaders", "required": ["Authorization", "If-Match"]},
        "successSchema": "AccommodationMutationResponse", "successStatuses": [200],
    },
    ("DELETE", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"): {
        "operation": "delete", "requestSchema": "AccommodationPath",
        "headersSchema": {"schema": "MutationHeaders", "required": ["Authorization", "If-Match"]},
        "successSchema": "none", "successStatuses": [204],
    },
}
EXPECTED_SCHEMA_GAP = [
    "trip_accommodations CHECK는 place_id/custom_name 둘 다 non-null을 허용하므로 XOR migration이 #68에 필요하다",
    "DB exclusion은 overlap만 막고 전체 gap/sequence compaction/active delete policy는 #68 application transaction이 소유한다",
    "public schema 단일 기준은 supabase/migrations이며 Flyway를 도입하지 않는다",
]
EXPECTED_ENDPOINT_FIGMA = {
    ("POST", "/api/v1/trips/{tripId}/accommodations"): {
        "node": "329-5165", "action": "숙소/복귀 위치 검색·지도 선택 후 여행 기본 조건 저장",
        "loading": "not-observed", "empty": "not-observed", "error": "not-observed",
    },
    ("PATCH", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"): {
        "node": "182-3248", "action": "숙소/복귀 위치를 검색 또는 지도 선택으로 변경",
        "loading": "not-observed", "empty": "not-observed", "error": "not-observed",
    },
    ("DELETE", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"): {
        "node": "not-observed", "action": "숙소 삭제 UI/action not-linked",
        "loading": "not-observed", "empty": "not-observed", "error": "not-observed",
    },
}
PROBLEM_FIELDS = {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"}
EXPECTED_PROBLEMS = {
    "INVALID_REQUEST": (400, "https://api.timing-jeju.com/problems/invalid-request", "요청 값이 올바르지 않습니다", "필수값, 형식, XOR, Idempotency-Key와 If-Match를 확인해 주세요.", "400_invalid_request"),
    "AUTHENTICATION_REQUIRED": (401, "https://api.timing-jeju.com/problems/authentication-required", "인증이 필요합니다", "로그인 후 다시 요청해 주세요.", "401_authentication_required"),
    "INVALID_ACCESS_TOKEN": (401, "https://api.timing-jeju.com/problems/invalid-access-token", "인증 정보가 올바르지 않습니다", "유효한 인증 정보로 다시 요청해 주세요.", "401_invalid_access_token"),
    "TRIP_NOT_FOUND": (404, "https://api.timing-jeju.com/problems/trip-not-found", "여행을 찾을 수 없습니다", "요청한 여행이 없거나 접근할 수 없습니다.", "404_trip_not_found"),
    "ACCOMMODATION_NOT_FOUND": (404, "https://api.timing-jeju.com/problems/accommodation-not-found", "숙소를 찾을 수 없습니다", "요청한 숙소가 없거나 해당 여행에 속하지 않습니다.", "404_accommodation_not_found"),
    "PLACE_NOT_FOUND": (404, "https://api.timing-jeju.com/problems/place-not-found", "장소를 찾을 수 없습니다", "요청한 숙소 장소가 없거나 사용할 수 없습니다.", "404_place_not_found"),
    "IDEMPOTENCY_KEY_REUSED": (409, "https://api.timing-jeju.com/problems/idempotency-key-reused", "멱등성 키가 다른 요청에 사용되었습니다", "새 Idempotency-Key로 다시 요청해 주세요.", "409_idempotency_key_reused"),
    "TRIP_VERSION_CONFLICT": (409, "https://api.timing-jeju.com/problems/trip-version-conflict", "여행 조건이 이미 변경되었습니다", "최신 여행과 ETag를 조회한 뒤 다시 요청해 주세요.", "409_trip_version_conflict"),
    "ACCOMMODATION_CONCURRENT_CONFLICT": (409, "https://api.timing-jeju.com/problems/accommodation-concurrent-conflict", "숙소가 동시에 변경되었습니다", "최신 숙소 순서와 기간을 조회한 뒤 다시 요청해 주세요.", "409_accommodation_concurrent_conflict"),
    "ACCOMMODATION_DATE_GAP_OR_OVERLAP": (422, "https://api.timing-jeju.com/problems/accommodation-date-gap-or-overlap", "숙소 날짜를 적용할 수 없습니다", "여행 기간 안에서 숙소 날짜의 공백과 중복 없이 순서를 확인해 주세요.", "422_date_gap_or_overlap"),
    "ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE": (422, "https://api.timing-jeju.com/problems/accommodation-in-use-by-active-schedule", "활성 일정에서 사용하는 숙소는 삭제할 수 없습니다", "일정을 재생성하거나 활성 일정을 해제한 뒤 숙소를 삭제해 주세요.", "422_active_schedule"),
}
EXPECTED_MATRIX = {
    ("POST", "/api/v1/trips/{tripId}/accommodations"): {
        "400": ["INVALID_REQUEST"], "401": ["AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN"],
        "404": ["TRIP_NOT_FOUND", "PLACE_NOT_FOUND"],
        "409": ["IDEMPOTENCY_KEY_REUSED", "TRIP_VERSION_CONFLICT", "ACCOMMODATION_CONCURRENT_CONFLICT"],
        "422": ["ACCOMMODATION_DATE_GAP_OR_OVERLAP"],
    },
    ("PATCH", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"): {
        "400": ["INVALID_REQUEST"], "401": ["AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN"],
        "404": ["TRIP_NOT_FOUND", "ACCOMMODATION_NOT_FOUND", "PLACE_NOT_FOUND"],
        "409": ["TRIP_VERSION_CONFLICT", "ACCOMMODATION_CONCURRENT_CONFLICT"],
        "422": ["ACCOMMODATION_DATE_GAP_OR_OVERLAP"],
    },
    ("DELETE", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"): {
        "400": ["INVALID_REQUEST"], "401": ["AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN"],
        "404": ["TRIP_NOT_FOUND", "ACCOMMODATION_NOT_FOUND"],
        "409": ["TRIP_VERSION_CONFLICT", "ACCOMMODATION_CONCURRENT_CONFLICT"],
        "422": ["ACCOMMODATION_DATE_GAP_OR_OVERLAP", "ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE"],
    },
}


class DuplicateKey(ValueError):
    pass


def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise DuplicateKey(f"duplicate key: {key}")
        value[key] = item
    return value


def _load(path: Path) -> Any:
    return json.loads(
        path.read_text(encoding="utf-8"), object_pairs_hook=_pairs,
        parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"non-finite: {value}")),
    )


def _non_empty(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _validate_schemas(contract: dict[str, Any], errors: list[str]) -> None:
    schemas = contract.get("schemas")
    if not isinstance(schemas, dict) or set(schemas) != SCHEMA_NAMES:
        errors.append("required schema exact 집합이 다릅니다.")
        return
    if any(not isinstance(schemas[name], dict) for name in SCHEMA_NAMES):
        errors.append("required schema object semantics가 다릅니다.")
        return
    for name in SCHEMA_NAMES:
        schema = schemas[name]
        if not isinstance(schema, dict) or schema.get("type") != "object" or schema.get("nullable") is not False or schema.get("additionalProperties") is not False:
            errors.append(f"{name} object/nullable/closed schema가 다릅니다.")
            continue
        expected_fields = {"type", "nullable", "additionalProperties", "required", "properties"}
        if name == "CreateAccommodationRequest":
            expected_fields.add("oneOf")
        elif name == "PatchAccommodationRequest":
            expected_fields.add("minProperties")
        if set(schema) != expected_fields or schema.get("properties") != EXPECTED_SCHEMA_PROPERTIES[name]:
            errors.append(f"{name} schema semantics가 canonical 값과 다릅니다.")
    expected_fields = {
        "TripPath": {"tripId"},
        "AccommodationPath": {"tripId", "accommodationId"},
        "CreateHeaders": {"Authorization", "Idempotency-Key", "If-Match"},
        "MutationHeaders": {"Authorization", "If-Match"},
    }
    for name, fields in expected_fields.items():
        schema = schemas[name]
        if set(schema.get("required", [])) != fields or set(schema.get("properties", {})) != fields:
            errors.append(f"{name} required/properties exact schema가 다릅니다.")
    create = schemas["CreateAccommodationRequest"]
    required = {"placeId", "customName", "checkInDate", "checkOutDate", "checkInTime", "checkOutTime"}
    if set(create.get("required", [])) != required:
        errors.append("required create accommodation 필드가 누락됐습니다.")
    if create.get("additionalProperties") is not False:
        errors.append("create schema additionalProperties=false가 필요합니다.")
    if set(create.get("properties", {})) != required:
        errors.append("CreateAccommodationRequest properties exact 집합이 다릅니다.")
    if create.get("oneOf") != [
        {"requiredNonNull": ["placeId"], "requiredNull": ["customName"]},
        {"requiredNonNull": ["customName"], "requiredNull": ["placeId"]},
    ]:
        errors.append("placeId/customName XOR schema가 다릅니다.")
    patch = schemas["PatchAccommodationRequest"]
    if patch.get("required") != [] or patch.get("minProperties") != 1:
        errors.append("PATCH null/omitted 최소 한 필드 경계가 다릅니다.")
    if patch.get("additionalProperties") is not False:
        errors.append("PATCH closed schema가 아닙니다.")
    if set(patch.get("properties", {})) != required:
        errors.append("PatchAccommodationRequest properties exact 집합이 다릅니다.")
    for name in ("CreateAccommodationRequest", "PatchAccommodationRequest", "Accommodation"):
        properties = schemas[name].get("properties", {})
        for field in ("checkInDate", "checkOutDate"):
            if properties.get(field, {}).get("format") != "date":
                errors.append(f"{name}.{field} date schema가 다릅니다.")
        for field in ("checkInTime", "checkOutTime"):
            if properties.get(field, {}).get("format") != "time":
                errors.append(f"{name}.{field} time schema가 다릅니다.")
    response = schemas["AccommodationMutationResponse"]
    if response.get("additionalProperties") is not False or set(response.get("required", [])) != set(response.get("properties", {})):
        errors.append("AccommodationMutationResponse required/closed schema가 다릅니다.")
    accommodation = schemas["Accommodation"]
    if set(accommodation.get("required", [])) != set(accommodation.get("properties", {})):
        errors.append("Accommodation nested required/closed schema가 다릅니다.")
    for name in ("CreateAccommodationRequest", "PatchAccommodationRequest", "Accommodation"):
        properties = schemas[name].get("properties", {})
        if properties.get("placeId", {}).get("nullable") is not True or properties.get("customName", {}).get("nullable") is not True:
            errors.append(f"{name} identity nullable schema가 다릅니다.")


def _validate_endpoints(contract: dict[str, Any], errors: list[str]) -> None:
    endpoints = contract.get("endpoints")
    if not isinstance(endpoints, list):
        errors.append("endpoints 배열이 필요합니다.")
        return
    identities: list[tuple[Any, Any]] = []
    for endpoint in endpoints:
        if not isinstance(endpoint, dict):
            errors.append("endpoint object가 필요합니다.")
            continue
        if set(endpoint) != ENDPOINT_FIELDS:
            errors.append("endpoint field required/unknown exact 집합이 다릅니다.")
        identity = (endpoint.get("method"), endpoint.get("path"))
        identities.append(identity)
        expected_schema = EXPECTED_ENDPOINT_SCHEMAS.get(identity)
        if expected_schema is None or any(
            endpoint.get(field) != expected_schema[field]
            for field in ("operation", "requestSchema", "successSchema")
        ) or endpoint.get("responses", {}).get("success") != expected_schema.get("successStatuses"):
            errors.append(f"{identity} endpoint schema refs/success status가 다릅니다.")
        if expected_schema is None or endpoint.get("headersSchema") != expected_schema.get("headersSchema"):
            errors.append(f"{identity} endpoint required headers가 다릅니다.")
        if endpoint.get("operation") not in {"create", "update", "delete"}:
            errors.append(f"{identity} operation이 다릅니다.")
        if endpoint.get("auth") != {"mode": "required", "missingToken": 401, "invalidToken": 401}:
            errors.append(f"{identity} auth가 #72와 다릅니다.")
        if endpoint.get("owner") != "canonical JWT sub; cross-owner 404":
            errors.append(f"{identity} owner canonical sub/404가 다릅니다.")
        if endpoint.get("concurrency") != "strong trip aggregate ETag":
            errors.append(f"{identity} concurrency가 다릅니다.")
        if endpoint.get("pagination") != {"type": "none"}:
            errors.append(f"{identity} pagination이 none이 아닙니다.")
        if endpoint.get("contractVersion") != "1.0.0":
            errors.append(f"{identity} contract version이 다릅니다.")
        if endpoint.get("errorMatrix") != EXPECTED_MATRIX.get(identity):
            errors.append(f"{identity} endpoint error matrix가 다릅니다.")
        response = endpoint.get("responses", {})
        matrix = endpoint.get("errorMatrix", {})
        if not isinstance(response, dict) or {str(item) for item in response.get("errors", [])} != set(matrix):
            errors.append(f"{identity} response/error matrix status가 다릅니다.")
        headers = endpoint.get("headersSchema")
        if not isinstance(headers, dict) or set(headers) != {"schema", "required"}:
            errors.append(f"{identity} headersSchema가 닫혀 있지 않습니다.")
        figma = endpoint.get("figma")
        if not isinstance(figma, dict) or set(figma) != {"node", "action", "loading", "empty", "error"} or not all(_non_empty(v) for v in figma.values()):
            errors.append(f"{identity} Figma linkage가 다릅니다.")
        elif figma != EXPECTED_ENDPOINT_FIGMA.get(identity):
            errors.append(f"{identity} Figma observed/not-linked evidence가 다릅니다.")
    if set(identities) != EXPECTED_ENDPOINTS or len(identities) != 3:
        errors.append("endpoint method/path duplicate 또는 exact 집합이 다릅니다.")
    post = next((e for e in endpoints if isinstance(e, dict) and e.get("method") == "POST"), {})
    if post.get("idempotency") != {"required": True, "header": "Idempotency-Key", "scope": "canonical sub + method + path + tripId", "ttl": "24 hours", "replay": "same payload returns original 201 status, body and ETag", "payloadConflict": "409 IDEMPOTENCY_KEY_REUSED", "concurrentRequest": "wait for first transaction then replay"}:
        errors.append("POST Idempotency-Key scope/TTL/replay 계약이 다릅니다.")
    for endpoint in endpoints:
        if isinstance(endpoint, dict) and endpoint.get("method") != "POST" and endpoint.get("idempotency") != {"required": False, "header": "none"}:
            errors.append("PATCH/DELETE idempotency 계약이 다릅니다.")


def _validate_policies(contract: dict[str, Any], errors: list[str]) -> None:
    expected = {
        "identityXor": "exactly one of placeId/customName", "timezone": "Asia/Seoul",
        "checkInOutTime": "HH:mm local wall-clock in Asia/Seoul", "dateInterval": "[checkInDate, checkOutDate)",
        "dateOrder": "checkInDate < checkOutDate", "tripCoverage": "within [trip.startDate, trip.endDate]",
        "gapOrOverlap": "reject-422", "canonicalOrder": "checkInDate ASC, checkOutDate ASC, accommodationId ASC",
        "sequencePolicy": "renumber contiguous 1..N in the same transaction",
    }
    if contract.get("accommodationPolicy") != expected:
        errors.append("숙소 XOR/timezone/coverage/gap/overlap/order 정책이 다릅니다.")
    patch = contract.get("patchPolicy", {})
    if patch.get("presence") != "omitted=unchanged; explicit null allowed only for the losing identity field" or patch.get("identityResult") != "result must preserve placeId/customName exact XOR":
        errors.append("PATCH null/omitted/XOR 정책이 다릅니다.")
    if patch.get("canonicalNoOp") != "return 200 with unchanged ETag and no schedule mutation":
        errors.append("PATCH canonical no-op ETag 정책이 다릅니다.")
    active = contract.get("activeSchedulePolicy")
    if active != {"postPatch": "actual change invalidates active schedule atomically", "delete": "reject-422 while an active schedule exists", "canonicalNoOp": "no-op preserves active schedule and ETag"}:
        errors.append("active schedule mutation/delete 정책이 다릅니다.")
    if contract.get("schemaGap") != EXPECTED_SCHEMA_GAP:
        errors.append("schemaGap canonical 값이 다릅니다.")


def _validate_errors(contract: dict[str, Any], errors: list[str]) -> None:
    conditions = contract.get("errorConditions")
    if not isinstance(conditions, list):
        errors.append("errorConditions 배열이 필요합니다.")
        return
    by_code = {item.get("code"): item for item in conditions if isinstance(item, dict)}
    if len(by_code) != len(conditions) or set(by_code) != set(EXPECTED_PROBLEMS):
        errors.append("errorConditions code duplicate/unknown/missing이 있습니다.")
    fields = {"status", "code", "type", "title", "detail", "instance", "condition", "fixture"}
    for code, (status, problem_type, title, detail, fixture) in EXPECTED_PROBLEMS.items():
        item = by_code.get(code)
        if not isinstance(item, dict):
            errors.append(f"{code} canonical condition이 누락됐습니다.")
            continue
        if set(item) != fields or item.get("status") != status or item.get("fixture") != fixture:
            errors.append(f"{code} problem fixture linkage가 다릅니다.")
        if item.get("type") != problem_type or item.get("title") != title or item.get("detail") != detail:
            errors.append(f"{code} problem canonical type/title/detail이 다릅니다.")
        if item.get("instance") != "urn:timing-jeju:problem:{traceId}" or not all(_non_empty(item.get(key)) for key in ("type", "title", "detail", "condition")):
            errors.append(f"{code} problem canonical field가 다릅니다.")
    referenced = {code for endpoint in contract.get("endpoints", []) if isinstance(endpoint, dict) for codes in endpoint.get("errorMatrix", {}).values() for code in codes}
    if referenced != set(EXPECTED_PROBLEMS):
        errors.append("endpoint matrix→condition 양방향 exact linkage가 다릅니다.")


def _validate_external(contract: dict[str, Any], errors: list[str]) -> None:
    external = contract.get("externalTraceability", {})
    notion = external.get("notion", {})
    expected_notion = {
        "databaseId": "40914d1e-551f-4cfc-9604-0190ecda7b6c",
        "dataSourceId": "d020c382-bb92-466e-8dcb-d6f95b486348",
        "contractVersion": "1.0.0", "specStatus": "Implementation Ready",
        "rows": [
            {"method": "POST", "path": "/api/v1/trips/{tripId}/accommodations", "pageId": "3a40a87c-7ce5-814f-b75f-e679f990786d", "url": "https://app.notion.com/p/3a40a87c7ce5814fb75fe679f990786d"},
            {"method": "PATCH", "path": "/api/v1/trips/{tripId}/accommodations/{accommodationId}", "pageId": "3a40a87c-7ce5-8185-b511-c4e7669a3ada", "url": "https://app.notion.com/p/3a40a87c7ce58185b511c4e7669a3ada"},
            {"method": "DELETE", "path": "/api/v1/trips/{tripId}/accommodations/{accommodationId}", "pageId": "3a40a87c-7ce5-81ba-88f1-f6acd7e8b9b9", "url": "https://app.notion.com/p/3a40a87c7ce581ba88f1f6acd7e8b9b9"},
        ],
    }
    if notion != expected_notion:
        errors.append("Notion/local contract version 또는 exact 3개 행 linkage가 다릅니다.")
    figma = external.get("figma", {})
    expected_figma = {
        "status": "not-ready", "contractVersion": "not-linked", "fileKey": "4mKep38zm17iupVSQVsSJW", "pageNodeId": "251:4347",
        "observedNodes": [
            {"nodeId": "329:5165", "name": "PAGE / 01. 여행 기본 조건", "action": "숙소/복귀 위치와 여행 기간을 입력하고 Day별 일정 입력으로 이동"},
            {"nodeId": "182:3248", "name": "홈 - 01. 여행 기본 조건", "action": "숙소/복귀 위치를 검색 또는 지도에서 선택"},
            {"nodeId": "653:11512", "name": "홈 - 01. 여행 기본 조건 설정 전", "action": "날짜 입력 후 숙소/복귀 위치를 설정"},
        ],
        "observedStates": ["여행 기본 조건", "기본 조건 설정 전", "날짜 입력 후"],
        "missingStateEvidence": ["loading", "empty", "error"],
        "reason": "숙소 입력 action은 관찰됐지만 삭제 UI/action, 복수 CRUD 상태, API contractVersion과 loading/empty/error response 연결은 관찰되지 않았다",
    }
    if figma != expected_figma:
        errors.append("Figma page/node/action/not-linked state exact linkage가 다릅니다.")
    expected_readiness = {stage: {"status": "not-ready", "evidence": None} for stage in ("metadata", "example", "implementation")}
    if contract.get("readiness") != expected_readiness:
        errors.append("Figma not-linked인데 readiness를 승격할 수 없습니다.")


def _catalog_projection(endpoint: dict[str, Any]) -> dict[str, Any]:
    request = endpoint["requestSchema"]
    path_schema, _, body = request.partition(" + ")
    method = endpoint["method"]
    return {
        "method": method, "path": endpoint["path"], "operation": endpoint["operation"],
        "auth": endpoint["auth"], "owner": "Spring Accommodations domain; canonical sub owner, cross-owner 404; implementation #68",
        "schemas": {"path": path_schema, "query": "none", "headers": endpoint["headersSchema"]["schema"], "body": "none" if method == "DELETE" else body},
        "presence": endpoint["presence"], "responses": endpoint["responses"], "dbOwner": endpoint["dbOwner"],
        "requestTimeCall": endpoint["requestTimeCall"], "dataLineage": endpoint["dataLineage"],
        "figma": endpoint["figma"], "contractVersion": endpoint["contractVersion"],
        "idempotency": endpoint["idempotency"], "pagination": endpoint["pagination"],
    }


def _validate_catalog(contract: dict[str, Any], errors: list[str]) -> None:
    try:
        catalog = _load(CATALOG)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        errors.append(f"catalog를 읽을 수 없습니다: {exc}")
        return
    actual = [e for e in catalog.get("endpoints", []) if (e.get("method"), e.get("path")) in EXPECTED_ENDPOINTS]
    expected = [_catalog_projection(e) for e in contract["endpoints"]]
    if actual != expected:
        errors.append("catalog endpoint projection이 canonical 계약과 다릅니다.")
    domain = next((item for item in catalog.get("domainContracts", []) if item.get("issue") == 87), None)
    expected_domain = {"issue": 87, "domain": "accommodations", "inherits": "timing-jeju-rest-contract/v1", "versions": {"local": "1.0.0", "notion": "1.0.0", "figma": "not-linked"}, "readiness": {stage: {"status": "not-ready", "evidence": None} for stage in ("metadata", "example", "implementation")}}
    if domain != expected_domain:
        errors.append("catalog Notion/local/Figma version 또는 readiness projection이 다릅니다.")


def _xor(place_id: Any, custom_name: Any) -> bool:
    return (place_id is None) != (custom_name is None)


def _validate_date_time_fields(body: Any, path: str, errors: list[str]) -> None:
    if not isinstance(body, dict):
        return
    for field in ("checkInDate", "checkOutDate"):
        if field not in body:
            continue
        value = body[field]
        try:
            parsed = date.fromisoformat(value) if isinstance(value, str) else None
        except ValueError:
            parsed = None
        if parsed is None or re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) is None:
            errors.append(f"{path}.{field} schema date format이 아닙니다.")
    for field in ("checkInTime", "checkOutTime"):
        if field in body and (not isinstance(body[field], str) or re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", body[field]) is None):
            errors.append(f"{path}.{field} schema time format이 아닙니다.")


def _validate_fixtures(contract: dict[str, Any], errors: list[str]) -> None:
    fixtures: dict[str, Any] = {}
    for name in ("request", "success", "problem"):
        try:
            fixtures[name] = _load(FIXTURES / f"{name}.json")
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"{name} fixture를 읽을 수 없습니다: {exc}")
            return
        if not isinstance(fixtures[name], dict) or fixtures[name].get("contractVersion") != contract.get("contractVersion"):
            errors.append(f"{name} fixture contract version이 다릅니다.")
    request_examples = fixtures["request"].get("examples", {})
    success_examples = fixtures["success"].get("examples", {})
    if set(request_examples) != {"create", "patch", "delete"} or set(success_examples) != {"create", "patch"}:
        errors.append("request/success fixture endpoint examples가 다릅니다.")
        return
    schemas = contract.get("schemas", {})
    create = request_examples.get("create", {})
    create_body = create.get("body")
    _validate_value(create_body, schemas.get("CreateAccommodationRequest"), schemas, "request fixture create.body", errors)
    _validate_date_time_fields(create_body, "request fixture create.body", errors)
    if not isinstance(create_body, dict) or not _xor(create_body.get("placeId"), create_body.get("customName")):
        errors.append("POST identity XOR endpoint semantics가 다릅니다.")
    create_headers = create.get("headers")
    _validate_value(create_headers, schemas.get("CreateHeaders"), schemas, "request fixture create.headers", errors)
    if not isinstance(create_headers, dict) or "Idempotency-Key" not in create_headers:
        errors.append("POST Idempotency-Key fixture가 누락됐습니다.")

    patch = request_examples.get("patch", {})
    patch_body = patch.get("body")
    _validate_value(patch_body, schemas.get("PatchAccommodationRequest"), schemas, "request fixture patch.body", errors)
    _validate_date_time_fields(patch_body, "request fixture patch.body", errors)
    if not isinstance(patch_body, dict) or not patch_body:
        errors.append("PATCH empty body는 거부해야 합니다.")
    existing = patch.get("existingIdentity")
    if isinstance(existing, dict) and isinstance(patch_body, dict):
        result_place = patch_body.get("placeId", existing.get("placeId"))
        result_name = patch_body.get("customName", existing.get("customName"))
        if not _xor(result_place, result_name):
            errors.append("PATCH identity result XOR endpoint semantics가 다릅니다.")
    patch_headers = patch.get("headers")
    _validate_value(patch_headers, schemas.get("MutationHeaders"), schemas, "request fixture patch.headers", errors)
    if not isinstance(patch_headers, dict) or "If-Match" not in patch_headers:
        errors.append("PATCH If-Match fixture가 누락됐습니다.")
    delete = request_examples.get("delete", {})
    if delete.get("body", "missing") is not None:
        errors.append("DELETE body forbidden endpoint semantics가 다릅니다.")

    response_schema = schemas.get("AccommodationMutationResponse")
    for name, expected_status in (("create", 201), ("patch", 200)):
        example = success_examples.get(name, {})
        body = example.get("body")
        if example.get("status") != expected_status:
            errors.append(f"success fixture {name} status가 다릅니다.")
        if isinstance(body, dict):
            allowed = set(response_schema.get("properties", {})) if isinstance(response_schema, dict) else set()
            extra = set(body) - allowed
            missing = set(response_schema.get("required", [])) - set(body) if isinstance(response_schema, dict) else set()
            if extra:
                errors.append(f"success fixture {name} 추가 response field가 있습니다: {', '.join(sorted(extra))}")
            if missing:
                errors.append(f"success fixture {name} schema required 필드가 누락됐습니다: {', '.join(sorted(missing))}")
        _validate_value(body, response_schema, schemas, f"success fixture {name}.body", errors)
        if isinstance(body, dict) and isinstance(body.get("accommodation"), dict):
            _validate_date_time_fields(body["accommodation"], f"success fixture {name}.body.accommodation", errors)

    problem_examples = fixtures["problem"].get("examples", {})
    expected_names = {value[4] for value in EXPECTED_PROBLEMS.values()}
    if not isinstance(problem_examples, dict) or set(problem_examples) != expected_names:
        errors.append("condition→problem fixture 양방향 exact 집합이 다릅니다.")
    conditions = {item.get("fixture"): item for item in contract.get("errorConditions", []) if isinstance(item, dict)}
    for name in expected_names:
        example = problem_examples.get(name) if isinstance(problem_examples, dict) else None
        condition = conditions.get(name)
        code = condition.get("code") if isinstance(condition, dict) else name
        if not isinstance(example, dict) or not isinstance(condition, dict):
            errors.append(f"{code} problem fixture canonical linkage가 누락됐습니다.")
            continue
        if set(example) != PROBLEM_FIELDS:
            errors.append(f"problem field exact 집합이 다릅니다: {name}")
            continue
        for field in ("type", "title", "status", "detail", "code"):
            if example.get(field) != condition.get(field):
                errors.append(f"{code} problem fixture {field}가 canonical condition과 다릅니다.")
        trace_id = example.get("traceId")
        if not isinstance(trace_id, str) or re.fullmatch(r"[0-9a-f]{32}", trace_id) is None:
            errors.append(f"{code} problem traceId가 다릅니다.")
        expected_instance = str(condition.get("instance", "")).replace("{traceId}", str(trace_id))
        if example.get("instance") != expected_instance:
            errors.append(f"{code} problem instance가 canonical condition과 다릅니다.")


def validate(contract: Any, skip_catalog_fixtures: bool = False) -> list[str]:
    if not isinstance(contract, dict):
        return ["contract는 object여야 합니다."]
    errors: list[str] = []
    if set(contract) != TOP_FIELDS:
        errors.append("contract top-level required/unknown field exact 집합이 다릅니다.")
    if contract.get("schemaVersion") != "timing-jeju-accommodations-contract/v1" or contract.get("contractVersion") != "1.0.0" or contract.get("sourceSpecVersion") != "v1.1" or contract.get("inherits") != "timing-jeju-rest-contract/v1" or contract.get("ownerIssue") != 87 or contract.get("implementationIssues") != [68]:
        errors.append("contract identity/version/inheritance가 다릅니다.")
    _validate_schemas(contract, errors)
    _validate_endpoints(contract, errors)
    _validate_policies(contract, errors)
    _validate_errors(contract, errors)
    _validate_external(contract, errors)
    if not skip_catalog_fixtures:
        _validate_catalog(contract, errors)
        _validate_fixtures(contract, errors)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="복수 숙소 CRUD 계약 검사")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--skip-catalog-fixtures", action="store_true")
    args = parser.parse_args()
    try:
        contract = _load(args.contract)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"복수 숙소 CRUD 계약 검사 실패: 계약 JSON을 읽을 수 없습니다: {exc}", file=sys.stderr)
        return 1
    errors = validate(contract, args.skip_catalog_fixtures)
    if errors:
        print("복수 숙소 CRUD 계약 검사 실패:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"복수 숙소 CRUD 계약 검사 성공: {args.contract}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
