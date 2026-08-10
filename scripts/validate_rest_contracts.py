#!/usr/bin/env python3
"""Timing Jeju REST 공통 계약 catalog의 구조와 readiness를 검사한다."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


REQUIRED_PROBLEM_FIELDS = {
    "type",
    "title",
    "status",
    "detail",
    "instance",
    "code",
    "traceId",
    "fieldErrors",
}
FORBIDDEN_PROBLEM_FIELDS = {"message", "violations"}
RUN_STATES = ["queued", "running", "succeeded", "failed", "cancelled"]
DOMAIN_ISSUES = set(range(82, 95))
AUTH_MODES = {"required", "optional"}
REQUIRED_ENDPOINT_FIELDS = {
    "method",
    "path",
    "auth",
    "owner",
    "schemas",
    "presence",
    "responses",
    "dbOwner",
    "requestTimeCall",
    "dataLineage",
    "figma",
    "contractVersion",
    "idempotency",
    "pagination",
}
REQUIRED_SCHEMAS = {"path", "query", "headers", "body"}
REQUIRED_FIGMA_FIELDS = {"node", "action", "loading", "empty", "error"}


def _non_empty(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def validate_catalog(catalog: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    contract_version = catalog.get("contractVersion")
    template_id = catalog.get("templateId")

    if not _non_empty(contract_version):
        errors.append("공통 계약 버전(contractVersion)이 필요합니다.")
    if not _non_empty(template_id):
        errors.append("공통 계약 templateId가 필요합니다.")

    _validate_common_rules(catalog.get("commonRules"), errors)
    _validate_ownership(catalog.get("ownership"), errors)
    _validate_endpoints(catalog.get("endpoints"), contract_version, errors)
    _validate_domains(
        catalog.get("domainContracts"), contract_version, template_id, errors
    )
    return errors


def _validate_common_rules(rules: Any, errors: list[str]) -> None:
    if not isinstance(rules, dict):
        errors.append("commonRules 객체가 필요합니다.")
        return

    authorization = rules.get("authorization", {})
    if set(authorization.get("modes", [])) != AUTH_MODES:
        errors.append("인증 mode는 required와 optional만 허용합니다.")
    if authorization.get("missingTokenCode") == authorization.get("invalidTokenCode"):
        errors.append("token 없음과 invalid token 오류 code를 구분해야 합니다.")

    idempotency = rules.get("idempotency", {})
    if idempotency.get("header") != "Idempotency-Key":
        errors.append("공통 멱등성 header는 Idempotency-Key여야 합니다.")
    required_idempotency = {
        "scope",
        "ttl",
        "replay",
        "payloadConflict",
        "concurrentRequest",
    }
    if set(idempotency.get("requires", [])) != required_idempotency:
        errors.append("멱등성 scope/TTL/replay/payload conflict/동시 요청 계약이 필요합니다.")

    cursor = rules.get("cursor", {})
    if cursor.get("cursor") != "opaque":
        errors.append("공통 cursor는 opaque여야 합니다.")
    cursor_requirements = set(cursor.get("requires", []))
    if cursor_requirements != {"size", "stableSort", "tieBreaker"}:
        errors.append("cursor는 size, stable sort, tie-breaker를 모두 요구해야 합니다.")

    problem = rules.get("problemDetails", {})
    fields = set(problem.get("fields", []))
    forbidden = set(problem.get("forbiddenFields", []))
    if fields != REQUIRED_PROBLEM_FIELDS:
        errors.append(
            "Problem Details 필드는 type,title,status,detail,instance,code,traceId,fieldErrors와 정확히 일치해야 합니다."
        )
    for name in sorted(FORBIDDEN_PROBLEM_FIELDS):
        if name in fields or name not in forbidden:
            errors.append(f"Problem Details에서 {name} 필드는 금지해야 합니다.")

    run = rules.get("asyncRun", {})
    if run.get("states") != RUN_STATES:
        errors.append(
            "비동기 run canonical 상태는 queued/running/succeeded/failed/cancelled 순서와 값만 허용합니다."
        )
    fallback = run.get("fallback", {})
    if (
        fallback.get("status") != "succeeded"
        or fallback.get("result_source") != "fallback"
    ):
        errors.append(
            "fallback 성공은 status=succeeded, result_source=fallback으로 표현해야 합니다."
        )
    if run.get("candidateExpiryField") != "expiresAt":
        errors.append("candidate 만료는 run 상태와 분리된 expiresAt 필드여야 합니다.")
    if run.get("headers") != ["Location", "Retry-After"]:
        errors.append("비동기 접수는 Location과 Retry-After header 계약을 가져야 합니다.")
    if not run.get("failureObjectFields"):
        errors.append("비동기 실패 응답의 failure object 필드를 정의해야 합니다.")

    hashes = rules.get("hashes", {})
    if hashes.get("commandInputHash") != "commandInputHash":
        errors.append("접수 hash 명칭은 commandInputHash여야 합니다.")
    if hashes.get("mcpInputHash") != "mcpInputHash":
        errors.append("MCP wire hash 명칭은 mcpInputHash여야 합니다.")
    if hashes.get("commandInputHash") == hashes.get("mcpInputHash"):
        errors.append("commandInputHash와 mcpInputHash는 분리해야 합니다.")


def _validate_ownership(ownership: Any, errors: list[str]) -> None:
    if not isinstance(ownership, dict):
        errors.append("후속 구현 소유권(ownership)이 필요합니다.")
        return
    expected = {
        "durableCommandSchema": 108,
        "locationCleanup": 109,
        "workerRuntime": 74,
    }
    for name, issue in expected.items():
        if ownership.get(name) != issue:
            errors.append(f"{name} 구현 소유자는 #{issue}여야 합니다.")


def _validate_endpoints(
    endpoints: Any, contract_version: Any, errors: list[str]
) -> None:
    if not isinstance(endpoints, list):
        errors.append("endpoints 배열이 필요합니다.")
        return

    identities: set[tuple[str, str]] = set()
    for index, endpoint in enumerate(endpoints):
        label = f"endpoints[{index}]"
        if not isinstance(endpoint, dict):
            errors.append(f"{label}는 객체여야 합니다.")
            continue
        missing = REQUIRED_ENDPOINT_FIELDS - endpoint.keys()
        for field in sorted(missing):
            errors.append(f"{label}에 필수 계약 필드 {field}가 없습니다.")

        method = str(endpoint.get("method", "")).upper()
        path = endpoint.get("path", "")
        identity = (method, path)
        if identity in identities:
            errors.append(f"endpoint method/path 중복: {method} {path}")
        identities.add(identity)

        if endpoint.get("contractVersion") != contract_version:
            errors.append(f"{label}의 contract version이 공통 버전과 다릅니다.")

        auth = endpoint.get("auth", {})
        if auth.get("mode") not in AUTH_MODES:
            errors.append(f"{label}의 인증 mode는 required 또는 optional이어야 합니다.")
        if auth.get("mode") == "required" and auth.get("missingToken") != 401:
            errors.append(f"{label}의 required 인증은 token 없음에 401이어야 합니다.")
        if auth.get("mode") == "optional" and auth.get("missingToken") != "anonymous":
            errors.append(f"{label}의 optional 인증은 token 없음에 anonymous여야 합니다.")
        if auth.get("invalidToken") != 401:
            errors.append(f"{label}은 invalid token에 401이어야 합니다.")

        schemas = endpoint.get("schemas", {})
        if not isinstance(schemas, dict) or not REQUIRED_SCHEMAS <= schemas.keys():
            errors.append(f"{label}은 path/query/header/body schema를 모두 명시해야 합니다.")
        figma = endpoint.get("figma", {})
        if not isinstance(figma, dict) or not REQUIRED_FIGMA_FIELDS <= figma.keys():
            errors.append(f"{label}은 Figma node/action/loading/empty/error를 모두 명시해야 합니다.")

        idempotency = endpoint.get("idempotency", {})
        if idempotency.get("required") and idempotency.get("header") != "Idempotency-Key":
            errors.append(f"{label}의 변경 요청은 Idempotency-Key를 상속해야 합니다.")

        pagination = endpoint.get("pagination", {})
        if pagination.get("type") == "cursor":
            if pagination.get("cursor") != "opaque":
                errors.append(f"{label}의 cursor는 opaque여야 합니다.")
            if not pagination.get("size"):
                errors.append(f"{label}의 cursor size 정책이 필요합니다.")
            if not pagination.get("stableSort"):
                errors.append(f"{label}의 stable sort가 필요합니다.")
            if not pagination.get("tieBreaker"):
                errors.append(f"{label}의 cursor tie-breaker가 필요합니다.")


def _validate_domains(
    domains: Any, contract_version: Any, template_id: Any, errors: list[str]
) -> None:
    if not isinstance(domains, list):
        errors.append("domainContracts 배열이 필요합니다.")
        return

    found_issues = {domain.get("issue") for domain in domains if isinstance(domain, dict)}
    for issue in sorted(DOMAIN_ISSUES - found_issues):
        errors.append(f"도메인 계약 #{issue}가 catalog에 없습니다.")
    for issue in sorted(found_issues - DOMAIN_ISSUES):
        errors.append(f"범위 밖 도메인 계약 #{issue}가 catalog에 있습니다.")

    for domain in domains:
        if not isinstance(domain, dict):
            errors.append("domainContracts 항목은 객체여야 합니다.")
            continue
        issue = domain.get("issue", "?")
        if domain.get("inherits") != template_id:
            errors.append(f"도메인 계약 #{issue}는 공통 템플릿을 명시적으로 상속해야 합니다.")

        versions = domain.get("versions", {})
        local = versions.get("local")
        notion = versions.get("notion")
        figma = versions.get("figma")
        if local != contract_version:
            errors.append(f"도메인 계약 #{issue}의 local contract version이 다릅니다.")
        for source, version in (("Notion", notion), ("Figma", figma)):
            if version not in {contract_version, "not-linked"}:
                errors.append(
                    f"도메인 계약 #{issue}의 Notion/Figma/local contract version drift: {source}={version}"
                )

        readiness = domain.get("readiness", {})
        allowed = {"ready", "not-ready"}
        for stage in ("metadata", "example", "implementation"):
            if readiness.get(stage) not in allowed:
                errors.append(f"도메인 계약 #{issue}의 {stage} readiness 값이 잘못되었습니다.")
        if readiness.get("metadata") == "ready" and (
            notion != contract_version or figma != contract_version
        ):
            errors.append(
                f"도메인 계약 #{issue}는 Notion/Figma/local 버전 일치 전 Metadata Ready가 될 수 없습니다."
            )
        if readiness.get("example") == "ready" and not domain.get("exampleEvidence"):
            errors.append(f"도메인 계약 #{issue}는 예시 증거 없이 Example Ready가 될 수 없습니다.")
        if readiness.get("implementation") == "ready" and not domain.get(
            "implementationEvidence"
        ):
            errors.append(
                f"도메인 계약 #{issue}는 구현 증거 없이 Implementation Ready가 될 수 없습니다."
            )


def main() -> int:
    parser = argparse.ArgumentParser(description="REST 공통 계약 readiness 검사")
    parser.add_argument(
        "catalog",
        nargs="?",
        type=Path,
        default=Path("docs/contracts/rest/catalog.json"),
    )
    args = parser.parse_args()

    try:
        catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"REST 계약 catalog를 읽을 수 없습니다: {error}", file=sys.stderr)
        return 2

    errors = validate_catalog(catalog)
    if errors:
        print("REST 계약 readiness 검사 실패:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(f"REST 계약 readiness 검사 성공: {args.catalog}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
