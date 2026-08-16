#!/usr/bin/env python3
"""Issue #88 불변 일정 조회·편집 canonical 계약을 fail-closed로 검사한다."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/schedules/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
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


def validate(contract_path: Path = DEFAULT_CONTRACT, skip_catalog_fixtures: bool = False) -> list[str]:
    errors: list[str] = []
    contract = _load(contract_path, "일정 계약", errors)
    if not isinstance(contract, dict):
        return errors or ["일정 계약은 JSON object여야 합니다."]

    if contract.get("schemaVersion") != "timing-jeju-schedules-contract/v1" or contract.get("contractVersion") != "1.0.0" or contract.get("inherits") != "timing-jeju-rest-contract/v1" or contract.get("ownerIssue") != 88 or contract.get("implementationIssues") != [49, 50, 51]:
        errors.append("identity/version/inheritance가 Issue #88 기준과 다릅니다.")

    schemas = contract.get("schemas", {})
    expected_schema_names = {"ScheduleQuery", "MutationHeaders", "CreateItemRequest", "PatchItemRequest", "DeleteItemQuery", "ReorderRequest", "MoveItemRequest", "ScheduleResponse", "MutationResponse"}
    if set(schemas) != expected_schema_names:
        errors.append("required/optional/null/omitted schema 집합이 다릅니다.")
    elif (
        schemas["CreateItemRequest"].get("required") != ["expectedActiveScheduleVersionId", "dayNo", "sequenceNo", "itemType", "plannedStartAt", "stayMinutes"]
        or schemas["PatchItemRequest"].get("presence") != "omitted=unchanged; memo alone nullable"
        or schemas["ReorderRequest"].get("required") != ["expectedActiveScheduleVersionId", "days"]
        or schemas["MoveItemRequest"].get("required") != ["expectedActiveScheduleVersionId", "targetDayNo", "targetSequenceNo", "plannedStartAt"]
        or schemas["MutationResponse"].get("constants") != {"sourceType": "user_edit", "feasibilityStale": True}
    ):
        errors.append("required/optional/null/omitted schema semantics가 다릅니다.")

    endpoints = contract.get("endpoints")
    identities = {(e.get("method"), e.get("path")) for e in endpoints} if isinstance(endpoints, list) and all(isinstance(e, dict) for e in endpoints) else set()
    if identities != EXPECTED_ENDPOINTS or not isinstance(endpoints, list) or len(endpoints) != 6:
        errors.append("endpoint 6개 method/path 범위가 정확하지 않습니다.")
        return errors
    if len(identities) != len(endpoints):
        errors.append("endpoint method/path duplicate가 있습니다.")

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
        matrix = endpoint.get("errorMatrix", {})
        if "ACTIVE_SCHEDULE_VERSION_CONFLICT" not in matrix.get("409", []) or "TRIP_VERSION_CONFLICT" not in matrix.get("409", []):
            errors.append(f"{identity} expected-version/If-Match 409가 모두 필요합니다.")

    policy = contract.get("mutationPolicy", {})
    if policy.get("expectedVersionLocation") != {"POST": "body", "PATCH": "body", "DELETE": "query", "PUT": "body"}:
        errors.append("expectedActiveScheduleVersionId 위치가 다릅니다.")
    if policy.get("concurrency") != "strong trip aggregate ETag plus expected active schedule version":
        errors.append("If-Match/expected version concurrency가 다릅니다.")
    if policy.get("validator") != "DB constraints and synchronous deterministic validator only" or not str(policy.get("aiCorrection", "")).startswith("never call MCP/AI"):
        errors.append("수동 편집 validator/AI 분리 경계가 다릅니다.")

    if contract.get("orderPolicy", {}).get("permutation") != PERMUTATION:
        errors.append("reorder permutation 계약이 다릅니다.")
    if contract.get("movePolicy", {}).get("dayBoundary") != "target day belongs to trip; local date of plannedStartAt equals target day date":
        errors.append("Day move boundary가 다릅니다.")
    if contract.get("versionPolicy", {}).get("legCompleteness") != "exactly one adjacent leg for every consecutive item pair; zero for fewer than two":
        errors.append("인접 leg 완전성 계약이 다릅니다.")

    item_policy = contract.get("itemPolicy", {})
    required = item_policy.get("requiredByType", {})
    if set(required) != {"place_visit", "meal", "accommodation", "arrival", "departure", "free_time", "custom"} or any(not fields for fields in required.values()):
        errors.append("itemType별 required field 계약이 닫히지 않았습니다.")
    if not str(item_policy.get("completedItem", "")).startswith("reject-422"):
        errors.append("완료 항목 변경 금지 계약이 필요합니다.")

    conditions = contract.get("errorConditions", [])
    condition_map = {c.get("code"): c for c in conditions} if isinstance(conditions, list) and all(isinstance(c, dict) for c in conditions) else {}
    for code in ("TRIP_NOT_FOUND", "SCHEDULE_ITEM_NOT_FOUND", "ACTIVE_SCHEDULE_VERSION_CONFLICT", "TRIP_VERSION_CONFLICT", "SCHEDULE_ITEM_COMPLETED"):
        if code not in condition_map:
            errors.append(f"오류 condition {code}가 누락됐습니다.")
    for condition in conditions if isinstance(conditions, list) else []:
        if not condition.get("title") or not condition.get("detail") or not any("가" <= ch <= "힣" for ch in condition.get("title", "") + condition.get("detail", "")):
            errors.append(f"한국어 Problem title/detail이 필요합니다: {condition.get('code')}")

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
        _validate_catalog(contract, errors)
        _validate_fixtures(contract, condition_map, errors)
    return errors


def _validate_catalog(contract: dict[str, Any], errors: list[str]) -> None:
    catalog = _load(CATALOG, "REST catalog", errors)
    if not isinstance(catalog, dict):
        return
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
    for key in ("createItem", "patchItem", "deleteItem", "reorder", "move"):
        if list(request[key].get("headers", {})) != MUTATION_HEADERS:
            errors.append(f"{key} fixture required headers가 다릅니다.")
    if "expectedActiveScheduleVersionId" not in request["deleteItem"].get("query", {}) or request["deleteItem"].get("body") is not None:
        errors.append("DELETE expected version query/body fixture가 다릅니다.")
    success = fixtures["success"]["examples"]
    if success["readActive"]["status"] != 200 or success["createItem"]["status"] != 201 or success["createItem"]["body"].get("sourceType") != "user_edit":
        errors.append("immutable read/new-version success fixture가 다릅니다.")
    problems = fixtures["problem"]["examples"]
    for code, condition in conditions.items():
        problem = problems.get(condition["fixture"])
        if not isinstance(problem, dict) or problem.get("code") != code or problem.get("status") != condition["status"] or problem.get("title") != condition["title"] or problem.get("detail") != condition["detail"]:
            errors.append(f"condition→problem fixture가 다릅니다: {code}")
        elif set(problem) != PROBLEM_FIELDS:
            errors.append(f"canonical Problem field가 다릅니다: {code}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--skip-catalog-fixtures", action="store_true")
    args = parser.parse_args()
    errors = validate(args.contract, args.skip_catalog_fixtures)
    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print("[OK] Issue #88 불변 일정 조회·편집 계약 검증 통과")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
