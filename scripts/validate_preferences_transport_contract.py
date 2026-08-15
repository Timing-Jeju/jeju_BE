#!/usr/bin/env python3
"""Issue #86 여행 선호·교통 이벤트 canonical 계약을 검사한다."""

from __future__ import annotations

import argparse
import json
import re
import sys
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
        if not isinstance(matrix, dict) or set(matrix) != {"400", "401", "404", "409", "422"} or not all(isinstance(v, list) and v and all(_non_empty(x) for x in v) for v in matrix.values()):
            errors.append(f"{identity} error matrix 조건이 누락됐습니다.")
        figma = endpoint.get("figma")
        if not isinstance(figma, dict) or set(figma) != {"node", "action", "loading", "empty", "error"} or not all(_non_empty(v) for v in figma.values()):
            errors.append(f"{identity} Figma node/action/state가 누락됐습니다.")
        for key in ("requestSchema", "headersSchema", "successSchema", "presence", "dbOwner", "requestTimeCall", "dataLineage"):
            if not _non_empty(endpoint.get(key)):
                errors.append(f"{identity} {key}가 비어 있습니다.")
    if set(identities) != EXPECTED_ENDPOINTS or len(identities) != 4:
        errors.append("endpoint method/path duplicate 또는 exact 집합이 다릅니다.")


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
    for name, example in fixtures["problem"].get("examples", {}).items():
        if not isinstance(example, dict) or set(example) != PROBLEM_FIELDS:
            errors.append(f"problem fixture {name} field exact 집합이 다릅니다.")
            continue
        if example.get("status") not in {400, 401, 404, 409, 422} or not re.fullmatch(r"[0-9a-f]{32}", str(example.get("traceId", ""))):
            errors.append(f"problem fixture {name} status/traceId가 다릅니다.")
        if example.get("instance") != f"urn:timing-jeju:problem:{example.get('traceId')}":
            errors.append(f"problem fixture {name} instance가 traceId와 다릅니다.")
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
