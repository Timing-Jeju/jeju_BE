#!/usr/bin/env python3
"""Issue #83 장소 검색·상세 계약과 추적성 fixture를 검사한다."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Callable


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
    "operationsSummary",
    "distanceMeters",
    "saved",
    "memo",
    "tags",
}
EXPECTED_DETAIL_SHARED_FIELDS = EXPECTED_LIST_FIELDS - {
    "distanceMeters",
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
FORBIDDEN_KEYS = {
    "rawtoken",
    "email",
    "apikey",
    "providerpayload",
    "servicerole",
    "jwtsecret",
    "freshnessreason",
}


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


def _validate_list_query(contract: dict[str, Any], errors: list[str]) -> None:
    endpoint = _find_endpoint(contract, "/api/v1/places")
    query = endpoint.get("query", {})
    geo = (
        query.get("lat", {}),
        query.get("lng", {}),
        query.get("radiusMeters", {}),
    )
    valid_geo = (
        geo[0].get("minimum") == -90
        and geo[0].get("maximum") == 90
        and geo[0].get("pairedWith") == "lng"
        and geo[1].get("minimum") == -180
        and geo[1].get("maximum") == 180
        and geo[1].get("pairedWith") == "lat"
        and geo[2].get("minimum") == 100
        and geo[2].get("maximum") == 50000
        and geo[2].get("default") == 10000
        and geo[2].get("requires") == ["lat", "lng"]
    )
    _expect(valid_geo, "lat/lng/radiusMeters 범위와 조합 계약이 다릅니다.", errors)

    size = query.get("size", {})
    cursor_query = query.get("cursor", {})
    _expect(
        size.get("minimum") == 1
        and size.get("maximum") == 100
        and size.get("default") == 20
        and cursor_query.get("type") == "opaque string",
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
        == {"recommendedStayMinutes", "thumbnailUrl", "operationsSummary"},
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
        nearby.get("freshBoundary") == "expiresAt > now()"
        and nearby.get("staleBoundary") == "expiresAt <= now()",
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
        readiness.get("baseContract") == "metadata+example ready"
        and readiness.get("implementation")
        == "not-ready until #66 Controller/OpenAPI/Repository/integration evidence",
        "#66 완료 전 nearbyStops extension은 Implementation Ready가 될 수 없습니다.",
        errors,
    )


def _validate_errors(contract: dict[str, Any], errors: list[str]) -> None:
    matrix = contract.get("errors")
    _expect(
        isinstance(matrix, dict)
        and set(matrix) == EXPECTED_ERROR_STATUSES
        and all(
            isinstance(codes, list)
            and codes
            and all(isinstance(code, str) and code for code in codes)
            for codes in matrix.values()
        ),
        "endpoint별 오류 matrix가 완전하지 않습니다.",
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
        and all(
            str(entry.get("url", "")).startswith("https://app.notion.com/p/")
            and str(entry.get("pageId", "")).replace("-", "")
            in str(entry.get("url", ""))
            for entry in notion_endpoints
        ),
        "Notion 두 endpoint page/version 추적성이 다릅니다.",
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
            loaded[kind] = json.loads((repo_root / relative).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            errors.append(f"{kind} fixture가 없거나 올바른 JSON이 아닙니다.")
    if set(loaded) != {"request", "success", "problem"}:
        return
    _expect(
        set(loaded["request"].get("endpoints", {})) == {"list", "detail"},
        "request fixture는 목록/상세 두 endpoint를 포함해야 합니다.",
        errors,
    )
    success = loaded["success"]
    detail = success.get("detail", {})
    _expect(
        set(success) == {"list", "detail"}
        and isinstance(success.get("list", {}).get("items"), list)
        and isinstance(detail.get("nearbyStops"), list)
        and detail.get("nearbyStops")
        and set(detail["nearbyStops"][0]) == set(EXPECTED_NEARBY_FIELDS),
        "success fixture의 목록/상세/nearbyStops shape가 다릅니다.",
        errors,
    )
    _expect(
        set(loaded["problem"]) == EXPECTED_ERROR_STATUSES
        and all(set(value) == PROBLEM_FIELDS for value in loaded["problem"].values()),
        "problem fixture가 오류 matrix와 Problem Details shape를 모두 포함해야 합니다.",
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
    _validate_identity(contract, errors)
    _validate_list_query(contract, errors)
    _validate_response(contract, errors)
    _validate_nearby_stops(contract, errors)
    _validate_errors(contract, errors)
    _validate_traceability(contract, errors, repo_root)
    _validate_fixtures(contract, errors, repo_root)
    return errors


def load_contract(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Issue #83 장소 REST 계약 검사")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        contract = load_contract(args.contract)
    except (OSError, json.JSONDecodeError) as error:
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
