#!/usr/bin/env python3
"""Issue #88 불변 일정 조회·편집 canonical 계약을 fail-closed로 검사한다."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

try:
    from scripts.validate_preferences_transport_contract import _validate_schema_value
except ModuleNotFoundError:  # direct `python3 scripts/...` execution
    from validate_preferences_transport_contract import _validate_schema_value

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/schedules/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
TEMPLATE = ROOT / "docs/contracts/rest/endpoint-template.json"
FIXTURES = ROOT / "fixtures/contracts/schedules"
EXPECTED_ENDPOINTS = {
    ("GET", "/api/v1/trips/{tripId}/schedule"),
    ("POST", "/api/v1/trips/{tripId}/schedule-items"),
    ("PATCH", "/api/v1/trips/{tripId}/schedule-items/{itemId}"),
    ("DELETE", "/api/v1/trips/{tripId}/schedule-items/{itemId}"),
    ("PUT", "/api/v1/trips/{tripId}/schedule-order"),
    ("POST", "/api/v1/trips/{tripId}/schedule-items/{itemId}/move"),
}
MUTATION_TRANSACTION = "new user_edit version; validate complete copy then CAS active pointer in one transaction"
MUTATION_HEADERS = ["Authorization", "Idempotency-Key", "If-Match"]
OWNER = "canonical JWT sub; cross-owner and wrong-trip resource 404"
PERMUTATION = "each active item ID exactly once across all submitted days; no missing, duplicate, foreign or extra ID"
PROBLEM_FIELDS = {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"}
ENDPOINT_IDEMPOTENCY = {
    "required": True,
    "header": "Idempotency-Key",
    "scope": "canonical sub + method + normalized path + tripId",
    "ttl": "24 hours from COMPLETED",
    "replay": "COMPLETED same hash replays stored status, ordered headers and body without executing the operation",
    "payloadConflict": "different hash returns immediate 409 IDEMPOTENCY_KEY_REUSED without Retry-After",
    "concurrentRequest": "PROCESSING lease-active same hash returns immediate 409 IDEMPOTENCY_KEY_REUSED with Retry-After: 1; never waits or replays",
}
MUTATION_IDEMPOTENCY = {
    "scope": "canonical sub + method + normalized path + Idempotency-Key",
    "processingLease": "2 minutes",
    "completedTtl": "24 hours from completion",
    "completedSameHash": ENDPOINT_IDEMPOTENCY["replay"],
    "differentHash": ENDPOINT_IDEMPOTENCY["payloadConflict"],
    "leaseActiveSameHash": ENDPOINT_IDEMPOTENCY["concurrentRequest"],
    "retryAfter": {"header": "Retry-After", "value": "1", "appliesTo": "PROCESSING lease-active same-hash concurrent loser only"},
}
IDEMPOTENCY_RESPONSE_HEADERS = {"differentHash": {}, "leaseActiveSameHash": {"Retry-After": "1"}}
EXPECTED_ERROR_CONDITIONS = {
    "INVALID_REQUEST": "request path/query/body or a non-idempotency header violates the bound closed schema",
    "IDEMPOTENCY_KEY_REQUIRED": "required Idempotency-Key header is missing",
    "IDEMPOTENCY_KEY_INVALID": "Idempotency-Key header is present but is not a canonical UUID",
    "SCHEDULE_ORDER_NOT_PERMUTATION": "reorder omits, duplicates, adds or references a foreign active item ID",
    "AUTHENTICATION_REQUIRED": "Authorization header is missing",
    "INVALID_ACCESS_TOKEN": "Bearer token is malformed, invalid or expired",
    "TRIP_NOT_FOUND": "trip is missing or cross-owner",
    "SCHEDULE_VERSION_NOT_FOUND": "schedule version is missing, cross-owner or wrong-trip",
    "SCHEDULE_ITEM_NOT_FOUND": "schedule item is missing, cross-owner or wrong-trip",
    "PLACE_NOT_FOUND": "place is missing or unavailable in the normalized place catalog",
    "TRIP_DAY_NOT_FOUND": "target day is missing, cross-owner or wrong-trip",
    "ACCOMMODATION_NOT_FOUND": "referenced accommodation is missing, cross-owner or wrong-trip",
    "TRANSPORT_EVENT_NOT_FOUND": "referenced transport event is missing, cross-owner or wrong-trip",
    "IDEMPOTENCY_KEY_REUSED": "same idempotency scope/key has a different request hash, or the same hash is still PROCESSING with an active lease",
    "TRIP_VERSION_CONFLICT": "If-Match does not equal the current strong trip aggregate ETag",
    "ACTIVE_SCHEDULE_VERSION_CONFLICT": "expectedActiveScheduleVersionId does not equal the current active schedule version",
    "TRIP_TERMINAL_STATE_CONFLICT": "trip is completed, cancelled or failed",
    "SCHEDULE_ITEM_INVALID": "item violates type-required fields, range, target day or time-window invariants",
    "SCHEDULE_ITEM_COMPLETED": "patch, delete, reorder or move targets an item whose progress status is completed",
    "SCHEDULE_LEG_INCOMPLETE": "an adjacent pair cannot be reused, derived from an eligible snapshot or conservatively synthesized",
}


def _reject_constant(value: str) -> None:
    raise ValueError(f"non-JSON constant: {value}")


def _pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate key: {key}")
        result[key] = value
    return result


def _load(path: Path, label: str, errors: list[str]) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), parse_constant=_reject_constant, object_pairs_hook=_pairs)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        errors.append(f"{label} JSON을 읽을 수 없습니다: {exc}")
        return None


def validate(contract_path: Path = DEFAULT_CONTRACT, skip_catalog_fixtures: bool = False, catalog_path: Path = CATALOG) -> list[str]:
    errors: list[str] = []
    contract = _load(contract_path, "일정 계약", errors)
    if not isinstance(contract, dict):
        return errors or ["일정 계약은 JSON object여야 합니다."]

    if contract.get("schemaVersion") != "timing-jeju-schedules-contract/v1" or contract.get("contractVersion") != "1.0.0" or contract.get("inherits") != "timing-jeju-rest-contract/v1" or contract.get("ownerIssue") != 88 or contract.get("implementationIssues") != [49, 50, 51]:
        errors.append("identity/version/inheritance가 Issue #88 기준과 다릅니다.")

    schemas = contract.get("schemas", {})
    expected_schema_names = {"TripPath", "ScheduleItemPath", "ScheduleQuery", "ReadHeaders", "MutationHeaders", "CreateItemRequest", "PatchItemRequest", "DeleteItemQuery", "ReorderDay", "ReorderRequest", "MoveItemRequest", "ScheduleVersion", "ItemProgress", "ScheduleItem", "ScheduleLeg", "ScheduleDay", "ScheduleResponse", "MutationResponse"}
    if set(schemas) != expected_schema_names:
        errors.append("OpenAPI schema 집합이 다릅니다.")
    elif (
        schemas["CreateItemRequest"].get("required") != ["expectedActiveScheduleVersionId", "dayNo", "sequenceNo", "itemType", "plannedStartAt", "stayMinutes"]
        or schemas["PatchItemRequest"].get("presence") != "omitted=unchanged; memo alone nullable"
        or schemas["ReorderRequest"].get("required") != ["expectedActiveScheduleVersionId", "days"]
        or schemas["MoveItemRequest"].get("required") != ["expectedActiveScheduleVersionId", "targetDayNo", "targetSequenceNo", "plannedStartAt"]
        or schemas["MutationResponse"].get("constants") != {"sourceType": "user_edit", "feasibilityStale": True}
    ):
        errors.append("required/optional/null/omitted schema semantics가 다릅니다.")
    if isinstance(schemas, dict):
        for name, schema in schemas.items():
            if not isinstance(schema, dict) or schema.get("type") != "object" or schema.get("nullable") is not False or schema.get("additionalProperties") is not False or not isinstance(schema.get("required"), list) or not isinstance(schema.get("properties"), dict):
                errors.append(f"OpenAPI schema {name}가 closed object가 아닙니다.")
        leg = schemas.get("ScheduleLeg", {}).get("properties", {})
        if leg.get("transportMode", {}).get("enum") != ["walk", "public_transit", "rental_car", "taxi"] or leg.get("plannedDepartureAt", {}).get("format") != "date-time":
            errors.append("OpenAPI schema ScheduleLeg type/format/enum이 다릅니다.")
        progress = schemas.get("ItemProgress", {}).get("properties", {})
        if progress.get("status", {}).get("enum") != ["planned", "active", "arrived", "completed", "skipped", "missed"]:
            errors.append("OpenAPI schema ItemProgress enum이 다릅니다.")
        create = schemas.get("CreateItemRequest", {}).get("properties", {})
        if create.get("stayMinutes") != {"type": "integer", "minimum": 1, "maximum": 1440, "nullable": False} or create.get("plannedStartAt") != {"type": "string", "format": "date-time", "offset": "+09:00", "nullable": False} or create.get("title", {}).get("nullable") is not False or create.get("memo", {}).get("nullable") is not True:
            errors.append("OpenAPI schema Create/Patch type/format/range/nullability가 다릅니다.")
        schedule_response = schemas.get("ScheduleResponse", {})
        if schedule_response.get("required") != ["tripId", "scheduleVersion", "days"] or schedule_response.get("properties", {}).get("scheduleVersion") != {"$ref": "ScheduleVersion", "nullable": False}:
            errors.append("OpenAPI schema ScheduleResponse binding/required가 다릅니다.")
        item = schemas.get("ScheduleItem", {})
        if item.get("required") != ["itemId", "sequenceNo", "itemType", "placeId", "title", "plannedStartAt", "plannedEndAt", "stayMinutes", "bufferAfterMinutes", "required", "memo", "progress"]:
            errors.append("OpenAPI schema ScheduleItem response shape가 다릅니다.")
        mutation = schemas.get("MutationResponse", {})
        if mutation.get("required") != ["tripId", "previousScheduleVersionId", "activeScheduleVersionId", "versionNo", "sourceType", "feasibilityStale", "changedItemIds", "etag", "updatedAt"] or mutation.get("constants") != {"sourceType": "user_edit", "feasibilityStale": True}:
            errors.append("OpenAPI schema MutationResponse/etag shape가 다릅니다.")
        source_types = schemas.get("ScheduleVersion", {}).get("properties", {}).get("sourceType", {}).get("enum")
        if source_types != ["initial", "user_edit", "ai_generation", "recovery", "live_recalculation"]:
            errors.append("sourceType enum이 DB trip_schedule_versions와 다릅니다.")
        key_schema = schemas.get("MutationHeaders", {}).get("properties", {}).get("Idempotency-Key")
        if key_schema != {"type": "string", "format": "uuid", "nullable": False}:
            errors.append("Idempotency-Key UUID schema가 api_idempotency_records와 다릅니다.")

    endpoints = contract.get("endpoints")
    identities = {(e.get("method"), e.get("path")) for e in endpoints} if isinstance(endpoints, list) and all(isinstance(e, dict) for e in endpoints) else set()
    if identities != EXPECTED_ENDPOINTS or not isinstance(endpoints, list) or len(endpoints) != 6:
        errors.append("endpoint 6개 method/path 범위가 정확하지 않습니다.")
        return errors
    if len(identities) != len(endpoints):
        errors.append("endpoint method/path duplicate가 있습니다.")

    expected_bindings = {
        ("GET", "/api/v1/trips/{tripId}/schedule"): ({"path": "TripPath", "query": "ScheduleQuery", "headers": "ReadHeaders", "body": "none"}, "ScheduleResponse"),
        ("POST", "/api/v1/trips/{tripId}/schedule-items"): ({"path": "TripPath", "query": "none", "headers": "MutationHeaders", "body": "CreateItemRequest"}, "MutationResponse"),
        ("PATCH", "/api/v1/trips/{tripId}/schedule-items/{itemId}"): ({"path": "ScheduleItemPath", "query": "none", "headers": "MutationHeaders", "body": "PatchItemRequest"}, "MutationResponse"),
        ("DELETE", "/api/v1/trips/{tripId}/schedule-items/{itemId}"): ({"path": "ScheduleItemPath", "query": "DeleteItemQuery", "headers": "MutationHeaders", "body": "none"}, "MutationResponse"),
        ("PUT", "/api/v1/trips/{tripId}/schedule-order"): ({"path": "TripPath", "query": "none", "headers": "MutationHeaders", "body": "ReorderRequest"}, "MutationResponse"),
        ("POST", "/api/v1/trips/{tripId}/schedule-items/{itemId}/move"): ({"path": "ScheduleItemPath", "query": "none", "headers": "MutationHeaders", "body": "MoveItemRequest"}, "MutationResponse"),
    }
    for endpoint in endpoints:
        binding = expected_bindings[(endpoint["method"], endpoint["path"])]
        if endpoint.get("schemas") != binding[0] or endpoint.get("successSchema") != binding[1]:
            errors.append(f"endpoint schema binding이 다릅니다: {endpoint['method']} {endpoint['path']}")

    read = next(e for e in endpoints if e["method"] == "GET")
    if 409 in read.get("responses", {}).get("errors", []) or read.get("concurrency") != "none" or not str(read.get("transaction", "")).startswith("read-only"):
        errors.append("read-only 조회에 409 또는 변경 동작이 포함됐습니다.")
    if read.get("versionSelector") != "active when versionId omitted; explicit version must belong to same owner/trip":
        errors.append("active/explicit immutable version selector가 다릅니다.")

    for endpoint in endpoints[1:]:
        identity = f"{endpoint['method']} {endpoint['path']}"
        if endpoint.get("requiredHeaders") != MUTATION_HEADERS:
            errors.append(f"{identity} required headers에 Authorization/Idempotency-Key/If-Match가 필요합니다.")
        if endpoint.get("expectedActiveScheduleVersionId") != "required UUID selector":
            errors.append(f"{identity} expected active version selector가 필요합니다.")
        if endpoint.get("transaction") != MUTATION_TRANSACTION:
            errors.append(f"{identity} 새 불변 version 원자 활성화 transaction이 필요합니다.")
        if endpoint.get("owner") != OWNER:
            errors.append(f"{identity} owner 404 은닉 정책이 다릅니다.")
        if endpoint.get("idempotency") != ENDPOINT_IDEMPOTENCY:
            errors.append(f"{identity} idempotency concurrentRequest/Retry-After 계약이 #72와 다릅니다.")
        matrix = endpoint.get("errorMatrix", {})
        required_conflicts = {
            "ACTIVE_SCHEDULE_VERSION_CONFLICT",
            "TRIP_VERSION_CONFLICT",
            "TRIP_TERMINAL_STATE_CONFLICT",
        }
        if not required_conflicts.issubset(matrix.get("409", [])):
            errors.append(f"{identity} expected-version/If-Match/terminal 409가 모두 필요합니다.")
        if endpoint["method"] in {"POST", "PATCH"} and endpoint["path"].endswith(("/schedule-items", "/{itemId}")):
            if not {"ACCOMMODATION_NOT_FOUND", "TRANSPORT_EVENT_NOT_FOUND"}.issubset(matrix.get("404", [])):
                errors.append(f"{identity} accommodation/transport owner 404 error condition이 필요합니다.")

    policy = contract.get("mutationPolicy", {})
    if policy.get("expectedVersionLocation") != {"POST": "body", "PATCH": "body", "DELETE": "query", "PUT": "body"}:
        errors.append("expectedActiveScheduleVersionId 위치가 다릅니다.")
    if policy.get("concurrency") != "strong trip aggregate ETag plus expected active schedule version":
        errors.append("If-Match/expected version concurrency가 다릅니다.")
    if policy.get("idempotency") != MUTATION_IDEMPOTENCY:
        errors.append("mutation idempotency policy가 endpoint #72 계약과 일치하지 않습니다.")
    if policy.get("validator") != "DB constraints and synchronous deterministic validator only" or not str(policy.get("aiCorrection", "")).startswith("never call MCP/AI"):
        errors.append("수동 편집 validator/AI 분리 경계가 다릅니다.")

    if contract.get("orderPolicy", {}).get("permutation") != PERMUTATION:
        errors.append("reorder permutation 계약이 다릅니다.")
    if contract.get("movePolicy", {}).get("dayBoundary") != "target day belongs to trip; local date of plannedStartAt equals target day date":
        errors.append("Day move boundary가 다릅니다.")
    if contract.get("versionPolicy", {}).get("legCompleteness") != "exactly one adjacent leg for every consecutive item pair; zero for fewer than two":
        errors.append("인접 leg 완전성 계약이 다릅니다.")
    if contract.get("versionPolicy", {}).get("immutable") != "existing version identity/content and child items/legs are never edited; only atomic draft-to-active and prior active-to-superseded status transitions are allowed":
        errors.append("불변 version과 허용 status transition 계약이 다릅니다.")

    leg_policy = contract.get("legDerivationPolicy", {})
    if leg_policy.get("sourcePriority") != ["reuse-unchanged-active-leg", "stored-route-snapshot", "conservative-walk-fallback", "reject-422"] or leg_policy.get("requestTimeCall") != "none" or leg_policy.get("durationInvariant") != "walkMinutes + waitMinutes + rideMinutes + transferMinutes" or leg_policy.get("stableFailure") != "422 SCHEDULE_LEG_INCOMPLETE; rollback draft, prior active pointer unchanged" or set(leg_policy.get("operations", {})) != {"add", "delete", "reorder", "move"}:
        errors.append("leg derivation 정책이 deterministic/fail-closed가 아닙니다.")
    mapping = leg_policy.get("itemIdentityMapping", {})
    if mapping.get("newIdentity") != "new UUID for every copied item in the new schedule version" or mapping.get("mapping") != "command-scoped bijection oldItemIdToNewItemId" or "new item IDs" not in str(leg_policy.get("reuse", "")) or any("oldItemIdToNewItemId" not in str(operation.get("identityMapping", "")) for operation in leg_policy.get("operations", {}).values()):
        errors.append("item identity mapping과 leg endpoint 재연결 계약이 다릅니다.")

    item_policy = contract.get("itemPolicy", {})
    required = item_policy.get("requiredByType", {})
    if set(required) != {"place_visit", "meal", "accommodation", "arrival", "departure", "free_time", "custom"} or any(not fields for fields in required.values()):
        errors.append("itemType별 required field 계약이 닫히지 않았습니다.")
    if not str(item_policy.get("completedItem", "")).startswith("reject-422"):
        errors.append("완료 항목 변경 금지 계약이 필요합니다.")

    conditions = contract.get("errorConditions", [])
    condition_map = {c.get("code"): c for c in conditions} if isinstance(conditions, list) and all(isinstance(c, dict) for c in conditions) else {}
    expected_condition_codes = set(EXPECTED_ERROR_CONDITIONS)
    if set(condition_map) != expected_condition_codes:
        errors.append("error condition/code 집합이 exact하지 않습니다.")
    if not {"IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID"}.issubset(condition_map):
        errors.append("idempotency required/invalid condition이 누락되거나 conflation 됐습니다.")
    for code in ("TRIP_NOT_FOUND", "SCHEDULE_ITEM_NOT_FOUND", "ACCOMMODATION_NOT_FOUND", "TRANSPORT_EVENT_NOT_FOUND", "ACTIVE_SCHEDULE_VERSION_CONFLICT", "TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT", "SCHEDULE_ITEM_COMPLETED"):
        if code not in condition_map:
            errors.append(f"오류 condition {code}가 누락됐습니다.")
    for condition in conditions if isinstance(conditions, list) else []:
        expected_fields = {"status", "code", "condition", "type", "title", "detail", "fixture"}
        if condition.get("code") == "IDEMPOTENCY_KEY_REUSED":
            expected_fields.add("responseHeaders")
        if set(condition) != expected_fields or condition.get("condition") != EXPECTED_ERROR_CONDITIONS.get(condition.get("code")) or not str(condition.get("type", "")).startswith("https://api.timing-jeju.com/problems/"):
            label = "idempotency condition" if condition.get("code") == "IDEMPOTENCY_KEY_REUSED" else "error condition/code/type"
            errors.append(f"{label}가 exact하지 않습니다: {condition.get('code')}")
        if condition.get("code") == "IDEMPOTENCY_KEY_REUSED" and (condition.get("status") != 409 or condition.get("type") != "https://api.timing-jeju.com/problems/idempotency-key-reused" or condition.get("detail") != "다른 요청이면 새 Idempotency-Key로 다시 보내고, 동일 요청이 처리 중이면 Retry-After 헤더의 초만큼 기다린 뒤 다시 요청해 주세요." or condition.get("responseHeaders") != IDEMPOTENCY_RESPONSE_HEADERS):
            errors.append("idempotency condition/status/type/detail/Retry-After가 exact하지 않습니다.")
        required_invalid = {
            "IDEMPOTENCY_KEY_REQUIRED": ("https://api.timing-jeju.com/problems/idempotency-key-required", "멱등성 키가 필요합니다", "Idempotency-Key 헤더를 입력해 주세요."),
            "IDEMPOTENCY_KEY_INVALID": ("https://api.timing-jeju.com/problems/idempotency-key-invalid", "멱등성 키가 유효하지 않습니다", "UUID 형식의 Idempotency-Key를 입력해 주세요."),
        }
        if condition.get("code") in required_invalid:
            expected_type, expected_title, expected_detail = required_invalid[condition["code"]]
            if condition.get("status") != 400 or (condition.get("type"), condition.get("title"), condition.get("detail")) != (expected_type, expected_title, expected_detail):
                errors.append(f"idempotency required/invalid Problem이 exact하지 않습니다: {condition.get('code')}")
        if not condition.get("title") or not condition.get("detail") or not any("가" <= ch <= "힣" for ch in condition.get("title", "") + condition.get("detail", "")):
            errors.append(f"한국어 Problem title/detail이 필요합니다: {condition.get('code')}")
    matrix_codes: set[str] = set()
    for endpoint in endpoints:
        for status, codes in endpoint.get("errorMatrix", {}).items():
            for code in codes:
                matrix_codes.add(code)
                if code not in condition_map or condition_map[code].get("status") != int(status):
                    errors.append(f"error condition/matrix status가 다릅니다: {endpoint['method']} {endpoint['path']} {code}")
        if endpoint.get("method") != "GET" and not {"INVALID_REQUEST", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID"}.issubset(set(endpoint.get("errorMatrix", {}).get("400", []))):
            errors.append(f"error condition/matrix idempotency required/invalid 400 code가 누락됐습니다: {endpoint.get('method')} {endpoint.get('path')}")
    if matrix_codes != expected_condition_codes:
        errors.append("error condition/matrix code 집합이 양방향 exact하지 않습니다.")

    external = contract.get("externalTraceability", {})
    notion = external.get("notion", {})
    figma = external.get("figma", {})
    readiness = contract.get("readiness", {})
    if notion.get("contractVersion") != "not-linked" or notion.get("status") != "not-ready" or notion.get("rows") != []:
        errors.append("Notion/Figma 외부 증거: Notion은 확인 전 not-linked여야 합니다.")
    if figma.get("contractVersion") != "not-linked" or figma.get("status") != "not-ready" or figma.get("observedNodes") != []:
        errors.append("Notion/Figma 외부 증거: Figma는 확인 전 not-linked여야 합니다.")
    expected_readiness = {stage: {"status": "not-ready", "evidence": None} for stage in ("metadata", "example", "implementation")}
    if readiness != expected_readiness:
        errors.append("Notion/Figma not-linked 상태에서 readiness를 승격할 수 없습니다.")

    if not skip_catalog_fixtures:
        _validate_catalog(contract, errors, catalog_path)
        _validate_fixtures(contract, condition_map, errors)
    return errors


def _validate_catalog(contract: dict[str, Any], errors: list[str], catalog_path: Path = CATALOG) -> None:
    catalog = _load(catalog_path, "REST catalog", errors)
    if not isinstance(catalog, dict):
        return
    template = _load(TEMPLATE, "REST endpoint template", errors)
    if not isinstance(template, dict):
        return
    fields = template.get("requiredEndpointFields", [])
    expected_endpoints = [{field: endpoint[field] for field in fields} for endpoint in contract["endpoints"]]
    actual_endpoints = [endpoint for endpoint in catalog.get("endpoints", []) if (endpoint.get("method"), endpoint.get("path")) in EXPECTED_ENDPOINTS]
    if actual_endpoints != expected_endpoints:
        errors.append("catalog endpoint projection이 domain 계약과 bidirectional하게 다릅니다.")
    domains = [d for d in catalog.get("domainContracts", []) if d.get("issue") == 88]
    expected = {"issue": 88, "domain": "schedules", "inherits": "timing-jeju-rest-contract/v1", "versions": {"local": "1.0.0", "notion": "not-linked", "figma": "not-linked"}, "readiness": contract["readiness"]}
    if domains != [expected]:
        errors.append("catalog Issue #88 Notion/Figma/local version 또는 readiness projection이 다릅니다.")


def _validate_fixtures(contract: dict[str, Any], conditions: dict[str, dict[str, Any]], errors: list[str]) -> None:
    fixtures = {name: _load(FIXTURES / f"{name}.json", f"{name} fixture", errors) for name in ("request", "success", "problem")}
    if any(not isinstance(value, dict) for value in fixtures.values()):
        return
    if any(value.get("contractVersion") != contract["contractVersion"] for value in fixtures.values()):
        errors.append("fixture contractVersion drift가 있습니다.")
    request = fixtures["request"]["examples"]
    if request["readActive"].get("body") is not None and "body" in request["readActive"]:
        errors.append("GET read fixture body는 금지됩니다.")
    read_segments = request["readActive"]["path"].split("/")
    _validate_schema_value({"tripId": read_segments[4]}, contract["schemas"]["TripPath"], contract["schemas"], "readActive path", errors)
    _validate_schema_value(request["readActive"].get("query", {}), contract["schemas"]["ScheduleQuery"], contract["schemas"], "readActive query", errors)
    _validate_schema_value(request["readActive"]["headers"], contract["schemas"]["ReadHeaders"], contract["schemas"], "readActive headers", errors)
    for key in ("createItem", "patchItem", "deleteItem", "reorder", "move"):
        if list(request[key].get("headers", {})) != MUTATION_HEADERS:
            errors.append(f"{key} fixture required headers가 다릅니다.")
    if "expectedActiveScheduleVersionId" not in request["deleteItem"].get("query", {}) or request["deleteItem"].get("body") is not None:
        errors.append("DELETE expected version query/body fixture가 다릅니다.")
    request_schemas = {
        "createItem": ("TripPath", "CreateItemRequest"),
        "patchItem": ("ScheduleItemPath", "PatchItemRequest"),
        "deleteItem": ("ScheduleItemPath", "DeleteItemQuery"),
        "reorder": ("TripPath", "ReorderRequest"),
        "move": ("ScheduleItemPath", "MoveItemRequest"),
    }
    for key, (path_schema, payload_schema) in request_schemas.items():
        example = request[key]
        segments = example["path"].split("/")
        path_value = {"tripId": segments[4]}
        if path_schema == "ScheduleItemPath":
            path_value["itemId"] = segments[6]
        _validate_schema_value(path_value, contract["schemas"][path_schema], contract["schemas"], f"{key} path", errors)
        _validate_schema_value(example["headers"], contract["schemas"]["MutationHeaders"], contract["schemas"], f"{key} headers", errors)
        payload = example.get("query") if key == "deleteItem" else example.get("body")
        _validate_schema_value(payload, contract["schemas"][payload_schema], contract["schemas"], f"{key} request", errors)
    success = fixtures["success"]["examples"]
    expected_idempotency_scenarios = {
        "completedSameHash": {"state": "COMPLETED", "hash": "same", "outcome": "replay stored status, ordered headers and body", "operationExecuted": False},
        "differentHash": {"hash": "different", "status": 409, "code": "IDEMPOTENCY_KEY_REUSED", "headers": {}},
        "leaseActiveSameHash": {"state": "PROCESSING", "lease": "active", "hash": "same", "status": 409, "code": "IDEMPOTENCY_KEY_REUSED", "headers": {"Retry-After": "1"}, "waited": False, "replayed": False},
    }
    if fixtures["success"].get("idempotencyScenarios") != expected_idempotency_scenarios:
        errors.append("idempotency completed/different-hash/lease-active fixture가 다릅니다.")
    if fixtures["success"].get("sourceTypeFixtures") != ["initial", "user_edit", "ai_generation", "recovery", "live_recalculation"]:
        errors.append("sourceType DB fixture 집합이 다릅니다.")
    if success["readActive"]["status"] != 200 or success["createItem"]["status"] != 201 or success["createItem"]["body"].get("sourceType") != "user_edit":
        errors.append("immutable read/new-version success fixture가 다릅니다.")
    _validate_schema_value(success["readActive"]["body"], contract["schemas"]["ScheduleResponse"], contract["schemas"], "readActive success", errors)
    for key in ("createItem", "patchItem", "deleteItem", "reorder", "move"):
        _validate_schema_value(success[key]["body"], contract["schemas"]["MutationResponse"], contract["schemas"], f"{key} success", errors)
    problems = fixtures["problem"]["examples"]
    if fixtures["problem"].get("responseHeaders", {}).get("409_idempotency_key_reused") != IDEMPOTENCY_RESPONSE_HEADERS:
        errors.append("IDEMPOTENCY_KEY_REUSED fixture Retry-After가 다릅니다.")
    for code, condition in conditions.items():
        problem = problems.get(condition["fixture"])
        if not isinstance(problem, dict) or problem.get("code") != code or problem.get("status") != condition["status"] or problem.get("type") != condition["type"] or problem.get("title") != condition["title"] or problem.get("detail") != condition["detail"]:
            errors.append(f"condition→problem fixture가 다릅니다: {code}")
        elif set(problem) != PROBLEM_FIELDS:
            errors.append(f"canonical Problem field가 다릅니다: {code}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--skip-catalog-fixtures", action="store_true")
    args = parser.parse_args()
    errors = validate(args.contract, args.skip_catalog_fixtures, args.catalog)
    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print("[OK] Issue #88 불변 일정 조회·편집 계약 검증 통과")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
