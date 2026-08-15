#!/usr/bin/env python3
"""Issue #86 여행 선호·교통 이벤트 canonical 계약을 검사한다."""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/preferences-transport/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
FIXTURES = ROOT / "fixtures/contracts/preferences-transport"
EXPECTED_ENDPOINTS = {
    ("PUT", "/api/v1/trips/{tripId}/preferences"),
    ("PUT", "/api/v1/trips/{tripId}/place-preferences"),
    ("PUT", "/api/v1/trips/{tripId}/transport-event"),
    ("DELETE", "/api/v1/trips/{tripId}/transport-event"),
}
COMMON_RESPONSE_FIELDS = {
    "tripId", "scheduleEffect", "regenerationRequired", "activeScheduleVersionId",
    "tripStatus", "updatedAt",
}
RESPONSE_CHILD_FIELDS = {
    "PreferencesResponse": {"preferences"},
    "PlacePreferencesResponse": {"items"},
    "TransportEventMutationResponse": {"eventType", "deleted", "event"},
}
EXPECTED_ENDPOINT_ERROR_CODES = {
    ("PUT", "/api/v1/trips/{tripId}/preferences"): {
        "400": ["INVALID_REQUEST"],
        "401": ["AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN"],
        "404": ["TRIP_NOT_FOUND", "PLACE_NOT_FOUND"],
        "409": ["TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT"],
        "422": ["PREFERENCE_CONSTRAINT_VIOLATION"],
    },
    ("PUT", "/api/v1/trips/{tripId}/place-preferences"): {
        "400": ["INVALID_REQUEST"],
        "401": ["AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN"],
        "404": ["TRIP_NOT_FOUND", "PLACE_NOT_FOUND"],
        "409": ["TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT"],
        "422": ["PLACE_PREFERENCE_CONSTRAINT_VIOLATION"],
    },
    ("PUT", "/api/v1/trips/{tripId}/transport-event"): {
        "400": ["INVALID_REQUEST"],
        "401": ["AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN"],
        "404": ["TRIP_NOT_FOUND", "PLACE_NOT_FOUND"],
        "409": ["TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT"],
        "422": ["TRANSPORT_EVENT_CONSTRAINT_VIOLATION"],
    },
    ("DELETE", "/api/v1/trips/{tripId}/transport-event"): {
        "400": ["INVALID_REQUEST"],
        "401": ["AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN"],
        "404": ["TRIP_NOT_FOUND", "TRANSPORT_EVENT_NOT_FOUND"],
        "409": ["TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT"],
        "422": ["TRANSPORT_EVENT_CONSTRAINT_VIOLATION"],
    },
}
EXPECTED_PROBLEMS = {
    "INVALID_REQUEST": (400, "https://api.timing-jeju.com/problems/invalid-request", "요청 값이 올바르지 않습니다", "필수값, 형식과 If-Match를 확인해 주세요.", "400_invalid_request"),
    "AUTHENTICATION_REQUIRED": (401, "https://api.timing-jeju.com/problems/authentication-required", "인증이 필요합니다", "로그인 후 다시 요청해 주세요.", "401_authentication_required"),
    "INVALID_ACCESS_TOKEN": (401, "https://api.timing-jeju.com/problems/invalid-access-token", "인증 정보가 올바르지 않습니다", "유효한 인증 정보로 다시 요청해 주세요.", "401_invalid_access_token"),
    "TRIP_NOT_FOUND": (404, "https://api.timing-jeju.com/problems/trip-not-found", "여행을 찾을 수 없습니다", "요청한 여행이 없거나 접근할 수 없습니다.", "404_trip_not_found"),
    "PLACE_NOT_FOUND": (404, "https://api.timing-jeju.com/problems/place-not-found", "장소를 찾을 수 없습니다", "요청한 장소가 없거나 사용할 수 없습니다.", "404_place_not_found"),
    "TRANSPORT_EVENT_NOT_FOUND": (404, "https://api.timing-jeju.com/problems/transport-event-not-found", "교통 이벤트를 찾을 수 없습니다", "삭제할 도착 또는 출발 교통 이벤트가 없습니다.", "404_transport_event_not_found"),
    "TRIP_VERSION_CONFLICT": (409, "https://api.timing-jeju.com/problems/trip-version-conflict", "여행 조건이 이미 변경되었습니다", "최신 여행과 ETag를 조회한 뒤 다시 요청해 주세요.", "409_trip_version_conflict"),
    "TRIP_TERMINAL_STATE_CONFLICT": (409, "https://api.timing-jeju.com/problems/trip-terminal-state-conflict", "종료된 여행은 변경할 수 없습니다", "완료, 취소 또는 실패한 여행 조건은 변경할 수 없습니다.", "409_trip_terminal_state_conflict"),
    "PREFERENCE_CONSTRAINT_VIOLATION": (422, "https://api.timing-jeju.com/problems/preference-constraint-violation", "여행 선호 조건을 처리할 수 없습니다", "중복 값과 교통수단 primary·priority를 확인해 주세요.", "422_preference_constraint"),
    "PLACE_PREFERENCE_CONSTRAINT_VIOLATION": (422, "https://api.timing-jeju.com/problems/place-preference-constraint-violation", "장소 선호 조건을 처리할 수 없습니다", "같은 장소의 희망·회피 중복과 적용 Day를 확인해 주세요.", "422_place_preference_constraint"),
    "TRANSPORT_EVENT_CONSTRAINT_VIOLATION": (422, "https://api.timing-jeju.com/problems/transport-event-constraint-violation", "교통 이벤트를 처리할 수 없습니다", "날짜, +09:00 시간대와 터미널 입력을 확인해 주세요.", "422_transport_event_constraint"),
}
PROBLEM_FIELDS = {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"}
ENDPOINT_FIELDS = {
    "method", "path", "operation", "requestSchema", "headersSchema", "successSchema",
    "auth", "owner", "presence", "responses", "errorMatrix", "idempotency", "pagination",
    "dbOwner", "requestTimeCall", "dataLineage", "figma", "contractVersion",
}
TOP_FIELDS = {
    "schemaVersion", "contractVersion", "sourceSpecVersion", "inherits", "ownerIssue",
    "implementationIssues", "schemas", "endpoints", "preferencePolicy",
    "placePreferencePolicy", "transportEventPolicy", "scheduleEffectPolicy",
    "errorConditions", "externalTraceability", "readiness", "schemaGap",
}


class DuplicateKey(ValueError):
    pass


def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKey(f"duplicate key: {key}")
        result[key] = value
    return result


def _load(path: Path) -> Any:
    return json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=_pairs,
        parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"non-finite: {value}")),
    )


def _non_empty(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _validate_schema(contract: dict[str, Any], errors: list[str]) -> None:
    schemas = contract.get("schemas")
    if not isinstance(schemas, dict):
        errors.append("schemas object가 필요합니다.")
        return
    required = {
        "TripPath", "MutationHeaders", "TransportMode", "PreferencesRequest",
        "PlacePreferenceItem", "PlacePreferencesRequest", "TransportEventRequest",
        "DeleteTransportEventQuery", "MutationResponse", "PreferencesResponse",
        "PlacePreferencesResponse", "TransportEventMutationResponse",
    }
    if set(schemas) != required:
        errors.append("required schema exact 집합이 다릅니다.")
        return
    preferences = schemas["PreferencesRequest"]
    expected_preferences = {
        "preferredCategories", "arrivalRegionCode", "departureRegionCode",
        "preferredRegionCodes", "startPlaceId", "endPlaceId", "transportModes",
    }
    if set(preferences.get("required", [])) != expected_preferences:
        errors.append("required preferences 필드가 누락됐습니다.")
    properties = preferences.get("properties", {})
    for field in expected_preferences - {"startPlaceId", "endPlaceId"}:
        if not isinstance(properties.get(field), dict) or properties[field].get("nullable") is not False:
            errors.append(f"null/omitted preferences.{field}는 거부해야 합니다.")
    place_item = schemas["PlacePreferenceItem"]
    if set(place_item.get("required", [])) != {"placeId", "type", "targetDayNo", "priority"}:
        errors.append("required place preference 필드가 누락됐습니다.")
    target_day = place_item.get("properties", {}).get("targetDayNo", {})
    if target_day.get("nullable") is not True or target_day.get("minimum") != 1 or target_day.get("maximum") != 30:
        errors.append("null/omitted targetDayNo 경계가 다릅니다.")
    event = schemas["TransportEventRequest"]
    expected_event = {"eventType", "transportType", "terminalPlaceId", "customTerminalName", "scheduledAt", "transportNumber", "note"}
    if set(event.get("required", [])) != expected_event:
        errors.append("required transport event 필드가 누락됐습니다.")
    if event.get("properties", {}).get("scheduledAt", {}).get("offset") != "+09:00":
        errors.append("transport event +09:00 timezone 경계가 다릅니다.")

    mutation = schemas["MutationResponse"]
    if not isinstance(mutation, dict):
        errors.append("MutationResponse 공통 schema object가 필요합니다.")
        return
    if set(mutation) != {"type", "nullable", "required", "properties"}:
        errors.append("MutationResponse 공통 schema field exact 집합이 다릅니다.")
    if set(mutation.get("required", [])) != COMMON_RESPONSE_FIELDS:
        errors.append("MutationResponse 공통 required 필드가 누락됐습니다.")
    if set(mutation.get("properties", {})) != COMMON_RESPONSE_FIELDS:
        errors.append("MutationResponse 공통 properties 필드가 누락되거나 추가됐습니다.")

    for name, unique_fields in RESPONSE_CHILD_FIELDS.items():
        response = schemas[name]
        if not isinstance(response, dict) or set(response) != {"type", "nullable", "unevaluatedProperties", "allOf"}:
            errors.append(f"{name} allOf closed-world schema field exact 집합이 다릅니다.")
            continue
        if response.get("type") != "object" or response.get("nullable") is not False or response.get("unevaluatedProperties") is not False:
            errors.append(f"{name} allOf closed-world unevaluatedProperties가 false여야 합니다.")
        all_of = response.get("allOf")
        if not isinstance(all_of, list) or len(all_of) != 2:
            errors.append(f"{name} allOf composition이 누락됐습니다.")
            continue
        if all_of[0] != {"$ref": "MutationResponse"}:
            errors.append(f"{name} $ref MutationResponse composition이 누락됐습니다.")
        child = all_of[1]
        if not isinstance(child, dict) or set(child) != {"type", "required", "properties"} or child.get("type") != "object":
            errors.append(f"{name} 고유 child schema가 다릅니다.")
            continue
        if set(child.get("required", [])) != unique_fields:
            errors.append(f"{name} 고유 required 필드가 누락됐습니다.")
        if set(child.get("properties", {})) != unique_fields:
            errors.append(f"{name} 고유 properties 필드가 누락되거나 추가됐습니다.")


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
            errors.append("endpoint required/unknown field exact 집합이 다릅니다.")
        identity = (endpoint.get("method"), endpoint.get("path"))
        identities.append(identity)
        if endpoint.get("operation") not in {"update", "delete"}:
            errors.append(f"{identity} operation이 잘못되었습니다.")
        if endpoint.get("contractVersion") != "1.0.0":
            errors.append(f"{identity} local contract version이 다릅니다.")
        if endpoint.get("auth") != {"mode": "required", "missingToken": 401, "invalidToken": 401}:
            errors.append(f"{identity} auth가 #72와 다릅니다.")
        if endpoint.get("owner") != "canonical JWT sub; cross-owner 404":
            errors.append(f"{identity} owner가 canonical sub가 아닙니다.")
        if endpoint.get("idempotency") != {"required": False, "header": "none"}:
            errors.append(f"{identity} update/delete idempotency 상속이 다릅니다.")
        if endpoint.get("pagination") != {"type": "none"}:
            errors.append(f"{identity} pagination은 none이어야 합니다.")
        responses = endpoint.get("responses", {})
        if responses != {"success": [200], "errors": [400, 401, 404, 409, 422]}:
            errors.append(f"{identity} response status matrix가 다릅니다.")
        matrix = endpoint.get("errorMatrix")
        if matrix != EXPECTED_ENDPOINT_ERROR_CODES.get(identity):
            expected_codes = EXPECTED_ENDPOINT_ERROR_CODES.get(identity, {})
            missing_codes = {
                code
                for status, codes in expected_codes.items()
                for code in codes
                if code not in (matrix.get(status, []) if isinstance(matrix, dict) else [])
            }
            suffix = f": {', '.join(sorted(missing_codes))}" if missing_codes else ""
            errors.append(f"{identity} endpoint error matrix canonical code가 다릅니다{suffix}")
        figma = endpoint.get("figma")
        if not isinstance(figma, dict) or set(figma) != {"node", "action", "loading", "empty", "error"} or not all(_non_empty(v) for v in figma.values()):
            errors.append(f"{identity} Figma node/action/state가 누락됐습니다.")
        for key in ("requestSchema", "headersSchema", "successSchema", "presence", "dbOwner", "requestTimeCall", "dataLineage"):
            if not _non_empty(endpoint.get(key)):
                errors.append(f"{identity} {key}가 비어 있습니다.")
    if set(identities) != EXPECTED_ENDPOINTS or len(identities) != 4:
        errors.append("endpoint method/path duplicate 또는 exact 집합이 다릅니다.")


def _validate_error_conditions(contract: dict[str, Any], errors: list[str]) -> None:
    conditions = contract.get("errorConditions")
    if not isinstance(conditions, list):
        errors.append("errorConditions 배열이 필요합니다.")
        return
    by_code = {
        item.get("code"): item
        for item in conditions
        if isinstance(item, dict) and _non_empty(item.get("code"))
    }
    if len(by_code) != len(conditions):
        errors.append("errorConditions code duplicate 또는 비정상 entry가 있습니다.")
    missing = set(EXPECTED_PROBLEMS) - set(by_code)
    extra = set(by_code) - set(EXPECTED_PROBLEMS)
    for code in sorted(missing):
        errors.append(f"{code} canonical errorConditions가 누락됐습니다.")
    if extra:
        errors.append(f"errorConditions unknown code가 있습니다: {', '.join(sorted(extra))}")
    fields = {"status", "code", "type", "title", "detail", "instance", "condition", "fixture"}
    for code, expected in EXPECTED_PROBLEMS.items():
        item = by_code.get(code)
        if not isinstance(item, dict):
            continue
        status, problem_type, title, detail, fixture = expected
        if set(item) != fields:
            errors.append(f"{code} problem fixture linkage field exact 집합이 다릅니다.")
        if item.get("status") != status:
            errors.append(f"{code} problem status가 다릅니다.")
        if item.get("type") != problem_type:
            errors.append(f"{code} problem type이 다릅니다.")
        if item.get("title") != title or item.get("detail") != detail:
            errors.append(f"{code} problem title/detail이 다릅니다.")
        if item.get("instance") != "urn:timing-jeju:problem:{traceId}":
            errors.append(f"{code} problem instance template이 다릅니다.")
        if item.get("fixture") != fixture:
            errors.append(f"{code} problem fixture linkage가 다릅니다.")
        if not _non_empty(item.get("condition")):
            errors.append(f"{code} canonical condition이 비어 있습니다.")

    referenced: set[str] = set()
    for endpoint in contract.get("endpoints", []):
        matrix = endpoint.get("errorMatrix") if isinstance(endpoint, dict) else None
        if not isinstance(matrix, dict):
            continue
        for codes in matrix.values():
            if isinstance(codes, list):
                referenced.update(code for code in codes if isinstance(code, str))
    if referenced != set(EXPECTED_PROBLEMS):
        missing_references = set(EXPECTED_PROBLEMS) - referenced
        errors.append(
            "endpoint matrix→condition 양방향 exact linkage가 다릅니다"
            + (f": {', '.join(sorted(missing_references))}" if missing_references else "")
        )
    for identity, matrix in EXPECTED_ENDPOINT_ERROR_CODES.items():
        for status, codes in matrix.items():
            for code in codes:
                condition = by_code.get(code, {})
                if condition.get("status") != int(status):
                    errors.append(f"{identity} {code} endpoint status→condition status가 다릅니다.")


def _validate_policies(contract: dict[str, Any], errors: list[str]) -> None:
    preference = contract.get("preferencePolicy", {})
    if preference.get("writeMode") != "full-replace" or preference.get("omittedRequiredField") != "reject" or preference.get("explicitNull") != "reject":
        errors.append("preferences required/null/omitted full-replace 경계가 다릅니다.")
    modes = preference.get("transportModes", {})
    if modes != {"enum": ["public_transit", "rental_car", "taxi"], "mode": "unique", "priority": "contiguous 1..N and unique", "primary": "exactly one; primary priority=1"}:
        errors.append("preferences duplicate/primary transport mode 규칙이 다릅니다.")
    place = contract.get("placePreferencePolicy", {})
    if place.get("samePlaceConflict") != "reject 422; a place cannot appear as both must_visit and avoid" or place.get("targetDayNo") != "1..tripDayCount or null" or place.get("priorityTieBreak") != "priority DESC, placeId ASC":
        errors.append("place preference duplicate/day/tie 규칙이 다릅니다.")
    transport = contract.get("transportEventPolicy", {})
    if transport.get("terminalXor") != "exactly one of terminalPlaceId/customTerminalName" or transport.get("timezone") != "Asia/Seoul" or transport.get("localDate") != "arrival=startDate; departure=endDate" or transport.get("deleteSelector") != "eventType query parameter required":
        errors.append("transport event timezone/date/terminal XOR/delete 규칙이 다릅니다.")
    effect = contract.get("scheduleEffectPolicy", {})
    active = effect.get("changedWithActiveSchedule", {})
    if active != {"scheduleEffect": "invalidated", "regenerationRequired": True} or effect.get("activeVersionTransition") != "superseded" or effect.get("activeScheduleVersionId") != "clear" or effect.get("tripStatusAfterInvalidation") != "draft":
        errors.append("active schedule invalidation/regeneration 규칙이 다릅니다.")


def _validate_external(contract: dict[str, Any], errors: list[str]) -> None:
    external = contract.get("externalTraceability", {})
    notion = external.get("notion", {})
    if notion.get("contractVersion") != contract.get("contractVersion"):
        errors.append("Notion/local contract version drift가 있습니다.")
    if notion.get("specStatus") != "Implementation Ready":
        errors.append("Notion Spec Status가 Implementation Ready가 아닙니다.")
    rows = notion.get("rows")
    if not isinstance(rows, list) or len(rows) != 4 or {(row.get("method"), row.get("path")) for row in rows if isinstance(row, dict)} != EXPECTED_ENDPOINTS or len({row.get("pageId") for row in rows if isinstance(row, dict)}) != 4:
        errors.append("Notion exact 4개 행 linkage가 다릅니다.")
    figma = external.get("figma", {})
    if figma.get("pageNodeId") != "251:4347" or figma.get("fileKey") != "4mKep38zm17iupVSQVsSJW":
        errors.append("Figma page/file evidence가 다릅니다.")
    if figma.get("contractVersion") != "not-linked" or figma.get("missingStateEvidence") != ["loading", "empty", "error"]:
        errors.append("Figma contractVersion/state not-ready 경계가 다릅니다.")
    nodes = figma.get("observedNodes")
    expected_nodes = {"329:5165", "182:3248", "653:11512", "329:4975"}
    if not isinstance(nodes, list) or {item.get("nodeId") for item in nodes if isinstance(item, dict)} != expected_nodes or not all(_non_empty(item.get("action")) for item in nodes if isinstance(item, dict)):
        errors.append("Figma node/action evidence가 다릅니다.")
    readiness = contract.get("readiness")
    not_ready = {stage: {"status": "not-ready", "evidence": None} for stage in ("metadata", "example", "implementation")}
    if readiness != not_ready:
        errors.append("Figma not-linked인데 readiness를 승격할 수 없습니다.")


def _catalog_endpoint(endpoint: dict[str, Any]) -> dict[str, Any]:
    request = endpoint["requestSchema"]
    path_schema, _, body_or_query = request.partition(" + ")
    is_delete = endpoint["method"] == "DELETE"
    return {
        "method": endpoint["method"], "path": endpoint["path"], "operation": endpoint["operation"],
        "auth": endpoint["auth"], "owner": "Spring Preferences Transport domain; canonical sub owner, cross-owner 404; implementation #46/#47",
        "schemas": {"path": path_schema, "query": body_or_query if is_delete else "none", "headers": endpoint["headersSchema"], "body": "none" if is_delete else body_or_query},
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
    catalog_endpoints = [item for item in catalog.get("endpoints", []) if (item.get("method"), item.get("path")) in EXPECTED_ENDPOINTS]
    expected = [_catalog_endpoint(item) for item in contract["endpoints"]]
    if catalog_endpoints != expected:
        errors.append("catalog endpoint projection이 canonical 계약과 다릅니다.")
    domain = next((item for item in catalog.get("domainContracts", []) if item.get("issue") == 86), None)
    expected_domain = {"issue": 86, "domain": "preferences-transport", "inherits": "timing-jeju-rest-contract/v1", "versions": {"local": "1.0.0", "notion": "1.0.0", "figma": "not-linked"}, "readiness": {stage: {"status": "not-ready", "evidence": None} for stage in ("metadata", "example", "implementation")}}
    if domain != expected_domain:
        errors.append("catalog Notion/local/Figma version 또는 readiness projection이 다릅니다.")


def _merge_schema(base: dict[str, Any], overlay: dict[str, Any]) -> dict[str, Any]:
    merged = dict(base)
    for key, value in overlay.items():
        if key == "properties" and isinstance(value, dict):
            merged[key] = {**merged.get(key, {}), **value}
        elif key == "required" and isinstance(value, list):
            merged[key] = list(dict.fromkeys([*merged.get(key, []), *value]))
        else:
            merged[key] = value
    return merged


def _flatten_schema(
    schema: Any,
    schemas: dict[str, Any],
    path: str,
    errors: list[str],
    resolving: tuple[str, ...] = (),
) -> dict[str, Any]:
    if not isinstance(schema, dict):
        errors.append(f"{path} schema object가 필요합니다.")
        return {}
    flattened: dict[str, Any] = {}
    reference = schema.get("$ref")
    if reference is not None:
        if not isinstance(reference, str) or reference not in schemas:
            errors.append(f"{path} schema $ref를 해석할 수 없습니다.")
        elif reference in resolving:
            errors.append(f"{path} schema $ref 순환 참조가 있습니다.")
        else:
            flattened = _flatten_schema(
                schemas[reference], schemas, f"{path}->$ref({reference})", errors,
                (*resolving, reference),
            )
    all_of = schema.get("allOf")
    if all_of is not None:
        if not isinstance(all_of, list) or not all_of:
            errors.append(f"{path} schema allOf가 비어 있습니다.")
        else:
            for index, component in enumerate(all_of):
                flattened = _merge_schema(
                    flattened,
                    _flatten_schema(component, schemas, f"{path}.allOf[{index}]", errors, resolving),
                )
    return _merge_schema(
        flattened,
        {key: value for key, value in schema.items() if key not in {"$ref", "allOf"}},
    )


def _validate_schema_value(
    value: Any,
    schema: Any,
    schemas: dict[str, Any],
    path: str,
    errors: list[str],
) -> None:
    flattened = _flatten_schema(schema, schemas, path, errors)
    if not flattened:
        return
    if value is None:
        if flattened.get("nullable") is not True:
            errors.append(f"{path} schema nullable=false인데 null입니다.")
        return

    expected_type = flattened.get("type")
    type_matches = {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
    }
    if expected_type not in type_matches or not type_matches[expected_type]:
        errors.append(f"{path} schema type {expected_type}과 값이 다릅니다.")
        return

    enum = flattened.get("enum")
    if isinstance(enum, list) and value not in enum:
        errors.append(f"{path} schema enum 밖의 값입니다.")

    if expected_type == "object":
        properties = flattened.get("properties")
        required = flattened.get("required", [])
        if not isinstance(properties, dict) or not isinstance(required, list):
            errors.append(f"{path} schema object properties/required가 잘못됐습니다.")
            return
        missing = set(required) - set(value)
        if missing:
            errors.append(f"{path} schema required 필드가 누락됐습니다: {', '.join(sorted(missing))}")
        closed = flattened.get("additionalProperties") is False or flattened.get("unevaluatedProperties") is False
        unknown = set(value) - set(properties)
        if closed and unknown:
            errors.append(f"{path} schema additionalProperties가 있습니다: {', '.join(sorted(unknown))}")
        for field, field_value in value.items():
            field_schema = properties.get(field)
            if isinstance(field_schema, dict):
                _validate_schema_value(field_value, field_schema, schemas, f"{path}.{field}", errors)
        return

    if expected_type == "array":
        minimum = flattened.get("minItems")
        maximum = flattened.get("maxItems")
        if isinstance(minimum, int) and len(value) < minimum:
            errors.append(f"{path} schema minItems보다 짧습니다.")
        if isinstance(maximum, int) and len(value) > maximum:
            errors.append(f"{path} schema maxItems보다 깁니다.")
        if flattened.get("uniqueItems") is True:
            encoded = [json.dumps(item, ensure_ascii=False, sort_keys=True, allow_nan=False) for item in value]
            if len(encoded) != len(set(encoded)):
                errors.append(f"{path} schema uniqueItems 중복이 있습니다.")
        item_schema = flattened.get("items")
        if isinstance(item_schema, dict):
            for index, item in enumerate(value):
                _validate_schema_value(item, item_schema, schemas, f"{path}[{index}]", errors)
        return

    if expected_type == "string":
        minimum = flattened.get("minLength")
        maximum = flattened.get("maxLength")
        if isinstance(minimum, int) and len(value) < minimum:
            errors.append(f"{path} schema minLength보다 짧습니다.")
        if isinstance(maximum, int) and len(value) > maximum:
            errors.append(f"{path} schema maxLength보다 깁니다.")
        pattern = flattened.get("pattern")
        if isinstance(pattern, str) and re.fullmatch(pattern, value) is None:
            errors.append(f"{path} schema pattern과 다릅니다.")
        if flattened.get("normalization") == "trim+nfc" and value != unicodedata.normalize("NFC", value.strip()):
            errors.append(f"{path} schema normalization과 다릅니다.")
        value_format = flattened.get("format")
        if value_format == "uuid":
            try:
                parsed = uuid.UUID(value)
            except (ValueError, AttributeError):
                errors.append(f"{path} schema UUID format이 아닙니다.")
            else:
                if str(parsed) != value.lower():
                    errors.append(f"{path} schema UUID format이 canonical이 아닙니다.")
        elif value_format == "date-time":
            date_time_pattern = r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$"
            try:
                parsed_time = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError:
                parsed_time = None
            if re.fullmatch(date_time_pattern, value) is None or parsed_time is None or parsed_time.tzinfo is None:
                errors.append(f"{path} schema date-time format이 아닙니다.")
        offset = flattened.get("offset")
        if isinstance(offset, str) and not value.endswith(offset):
            errors.append(f"{path} schema offset {offset}가 아닙니다.")
        return

    if expected_type == "integer":
        minimum = flattened.get("minimum")
        maximum = flattened.get("maximum")
        if isinstance(minimum, int) and value < minimum:
            errors.append(f"{path} schema minimum보다 작습니다.")
        if isinstance(maximum, int) and value > maximum:
            errors.append(f"{path} schema maximum보다 큽니다.")


def _validate_fixtures(contract: dict[str, Any], errors: list[str]) -> None:
    fixtures: dict[str, Any] = {}
    for name in ("request", "success", "problem"):
        try:
            fixtures[name] = _load(FIXTURES / f"{name}.json")
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            errors.append(f"{name} fixture를 읽을 수 없습니다: {exc}")
            return
        if fixtures[name].get("contractVersion") != contract.get("contractVersion"):
            errors.append(f"{name} fixture contract version이 다릅니다.")
    example_keys = {"preferences", "placePreferences", "putTransportEvent", "deleteTransportEvent"}
    if set(fixtures["request"].get("examples", {})) != example_keys or set(fixtures["success"].get("examples", {})) != example_keys:
        errors.append("request/success fixture endpoint examples가 다릅니다.")
    problem_examples = fixtures["problem"].get("examples", {})
    expected_fixture_names = {value[4] for value in EXPECTED_PROBLEMS.values()}
    if not isinstance(problem_examples, dict) or set(problem_examples) != expected_fixture_names:
        missing_names = expected_fixture_names - set(problem_examples if isinstance(problem_examples, dict) else {})
        missing_codes = [code for code, value in EXPECTED_PROBLEMS.items() if value[4] in missing_names]
        errors.append(
            "condition→problem fixture 양방향 exact 집합이 다릅니다"
            + (f": {', '.join(sorted(missing_codes))}" if missing_codes else "")
        )
    for name, example in problem_examples.items() if isinstance(problem_examples, dict) else ():
        if not isinstance(example, dict) or set(example) != PROBLEM_FIELDS:
            errors.append(f"problem fixture {name} field exact 집합이 다릅니다.")
            continue
        if example.get("status") not in {400, 401, 404, 409, 422} or not re.fullmatch(r"[0-9a-f]{32}", str(example.get("traceId", ""))):
            errors.append(f"problem fixture {name} status/traceId가 다릅니다.")
        if example.get("instance") != f"urn:timing-jeju:problem:{example.get('traceId')}":
            errors.append(f"problem fixture {name} instance가 traceId와 다릅니다.")
    conditions = {
        item.get("fixture"): item
        for item in contract.get("errorConditions", [])
        if isinstance(item, dict) and _non_empty(item.get("fixture"))
    }
    for name in expected_fixture_names:
        example = problem_examples.get(name) if isinstance(problem_examples, dict) else None
        condition = conditions.get(name)
        code = condition.get("code") if isinstance(condition, dict) else next(
            (candidate for candidate, value in EXPECTED_PROBLEMS.items() if value[4] == name), name
        )
        if not isinstance(example, dict) or not isinstance(condition, dict):
            errors.append(f"{code} problem fixture canonical linkage가 누락됐습니다.")
            continue
        for field in ("status", "code", "type", "title", "detail"):
            if example.get(field) != condition.get(field):
                errors.append(f"{code} problem fixture {field}가 canonical condition과 다릅니다.")
        trace_id = example.get("traceId")
        expected_instance = str(condition.get("instance", "")).replace("{traceId}", str(trace_id))
        if example.get("instance") != expected_instance:
            errors.append(f"{code} problem fixture instance가 canonical condition과 다릅니다.")

    schemas = contract.get("schemas", {})
    success_schema_by_example = {
        "preferences": "PreferencesResponse",
        "placePreferences": "PlacePreferencesResponse",
        "putTransportEvent": "TransportEventMutationResponse",
        "deleteTransportEvent": "TransportEventMutationResponse",
    }
    common = schemas.get("MutationResponse", {})
    common_required = set(common.get("required", [])) if isinstance(common, dict) else set()
    common_properties = set(common.get("properties", {})) if isinstance(common, dict) else set()
    for example_name, schema_name in success_schema_by_example.items():
        example = fixtures["success"].get("examples", {}).get(example_name, {})
        body = example.get("body") if isinstance(example, dict) else None
        response = schemas.get(schema_name, {})
        all_of = response.get("allOf") if isinstance(response, dict) else None
        child = all_of[1] if isinstance(all_of, list) and len(all_of) == 2 and isinstance(all_of[1], dict) else {}
        allowed = common_properties | set(child.get("properties", {}))
        required = common_required | set(child.get("required", []))
        if not isinstance(body, dict):
            errors.append(f"success fixture {example_name} body object가 필요합니다.")
            continue
        extra_fields = set(body) - allowed
        missing_fields = required - set(body)
        if extra_fields:
            errors.append(f"success fixture {example_name} 추가 response field가 있습니다: {', '.join(sorted(extra_fields))}")
        if missing_fields:
            errors.append(f"success fixture {example_name} 누락 response field가 있습니다: {', '.join(sorted(missing_fields))}")
        _validate_schema_value(
            body,
            response,
            schemas,
            f"success fixture {example_name}.body",
            errors,
        )

    put_body = fixtures["success"].get("examples", {}).get("putTransportEvent", {}).get("body", {})
    if not isinstance(put_body, dict) or put_body.get("event") is None:
        errors.append("PUT event non-null endpoint semantics가 다릅니다.")
    if not isinstance(put_body, dict) or put_body.get("deleted") is not False:
        errors.append("PUT deleted=false endpoint semantics가 다릅니다.")
    if isinstance(put_body, dict) and isinstance(put_body.get("event"), dict) and put_body.get("eventType") != put_body["event"].get("eventType"):
        errors.append("PUT response/event eventType endpoint semantics가 다릅니다.")

    delete_body = fixtures["success"].get("examples", {}).get("deleteTransportEvent", {}).get("body", {})
    if not isinstance(delete_body, dict) or delete_body.get("event") is not None:
        errors.append("DELETE event null endpoint semantics가 다릅니다.")
    if not isinstance(delete_body, dict) or delete_body.get("deleted") is not True:
        errors.append("DELETE deleted=true endpoint semantics가 다릅니다.")
    success_delete = fixtures["success"].get("examples", {}).get("deleteTransportEvent", {})
    if success_delete.get("status") != 200 or success_delete.get("body", {}).get("regenerationRequired") is not True:
        errors.append("DELETE success fixture regeneration signal이 누락됐습니다.")


def validate(contract: Any, skip_catalog_fixtures: bool = False) -> list[str]:
    errors: list[str] = []
    if not isinstance(contract, dict):
        return ["contract는 object여야 합니다."]
    if set(contract) != TOP_FIELDS:
        errors.append("contract required/unknown top-level field exact 집합이 다릅니다.")
    if contract.get("schemaVersion") != "timing-jeju-preferences-transport-contract/v1" or contract.get("contractVersion") != "1.0.0" or contract.get("inherits") != "timing-jeju-rest-contract/v1" or contract.get("ownerIssue") != 86:
        errors.append("계약 identity/version/inheritance가 다릅니다.")
    _validate_schema(contract, errors)
    _validate_endpoints(contract, errors)
    _validate_error_conditions(contract, errors)
    _validate_policies(contract, errors)
    _validate_external(contract, errors)
    if not skip_catalog_fixtures:
        _validate_catalog(contract, errors)
        _validate_fixtures(contract, errors)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="여행 선호·교통 이벤트 계약 검사")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--skip-catalog-fixtures", action="store_true")
    args = parser.parse_args()
    try:
        contract = _load(args.contract)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"여행 선호·교통 이벤트 계약 검사 실패: 계약 JSON을 읽을 수 없습니다: {exc}", file=sys.stderr)
        return 1
    errors = validate(contract, args.skip_catalog_fixtures)
    if errors:
        print("여행 선호·교통 이벤트 계약 검사 실패:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"여행 선호·교통 이벤트 계약 검사 성공: {args.contract}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
