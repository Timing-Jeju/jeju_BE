#!/usr/bin/env python3
"""Timing Jeju REST 공통 계약 catalog와 template의 readiness를 검사한다."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any


CATALOG_VERSION = "rest-contract-catalog/v1"
TEMPLATE_ID = "timing-jeju-rest-contract/v1"
AUTH_MODES = {"required", "optional"}
ALLOWED_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE"}
ALLOWED_OPERATIONS = {"read", "list", "create", "update", "delete", "compute", "apply"}
IDEMPOTENT_OPERATIONS = {"create", "compute", "apply"}
RUN_STATES = ["queued", "running", "succeeded", "failed", "cancelled"]
DOMAIN_ISSUES = set(range(82, 95))
PATH_PATTERN = re.compile(
    r"^/api/v1(?:/(?:[A-Za-z0-9._~-]+|\{[A-Za-z][A-Za-z0-9]*\}))+?$"
)
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
FAILURE_OBJECT_FIELDS = {"code", "detail", "retryable"}
REQUIRED_ENDPOINT_FIELDS = {
    "method",
    "path",
    "operation",
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
REQUIRED_IDEMPOTENCY_FIELDS = {
    "required",
    "header",
    "scope",
    "ttl",
    "replay",
    "payloadConflict",
    "concurrentRequest",
}
READINESS_STAGES = ("metadata", "example", "implementation")
READINESS_EVIDENCE_FIELDS = {
    "metadata": {"localDocument", "notionPage", "figmaNode"},
    "example": {"requestFixture", "successFixture", "problemFixture"},
    "implementation": {"controller", "openApiTest", "contractTest"},
}
TEMPLATE_DEFAULTS = {
    "auth": {"mode": "required", "missingToken": 401, "invalidToken": 401},
    "idempotency": {"required": False, "header": "none"},
    "pagination": {"type": "none"},
}


def _non_empty(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _exact_non_empty_mapping(value: Any, fields: set[str]) -> bool:
    return (
        isinstance(value, dict)
        and set(value) == fields
        and all(_non_empty(item) for item in value.values())
    )


def _exact_string_list(value: Any, expected: set[str]) -> bool:
    return (
        isinstance(value, list)
        and len(value) == len(expected)
        and all(isinstance(item, str) for item in value)
        and set(value) == expected
    )


def _object(value: Any, label: str, errors: list[str]) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    errors.append(f"{label}은 객체여야 합니다.")
    return {}


def validate_catalog(catalog: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    contract_version = catalog.get("contractVersion")
    template_id = catalog.get("templateId")

    if catalog.get("catalogVersion") != CATALOG_VERSION:
        errors.append(f"catalogVersion은 {CATALOG_VERSION}이어야 합니다.")
    if not _non_empty(contract_version):
        errors.append("공통 계약 버전(contractVersion)이 필요합니다.")
    if template_id != TEMPLATE_ID:
        errors.append(f"공통 계약 templateId는 {TEMPLATE_ID}이어야 합니다.")

    _validate_common_rules(catalog.get("commonRules"), errors)
    _validate_ownership(catalog.get("ownership"), errors)
    _validate_endpoints(catalog.get("endpoints"), contract_version, errors)
    _validate_domains(catalog.get("domainContracts"), contract_version, template_id, errors)
    return errors


def _validate_common_rules(rules: Any, errors: list[str]) -> None:
    if not isinstance(rules, dict):
        errors.append("commonRules 객체가 필요합니다.")
        return

    authorization = _object(rules.get("authorization"), "commonRules.authorization", errors)
    if not _exact_string_list(authorization.get("modes"), AUTH_MODES):
        errors.append("인증 mode는 required와 optional만 허용합니다.")
    exact_authorization = {
        "principal": "canonical JWT sub",
        "missingTokenCode": "AUTHENTICATION_REQUIRED",
        "invalidTokenCode": "INVALID_ACCESS_TOKEN",
    }
    for field, expected in exact_authorization.items():
        if authorization.get(field) != expected:
            errors.append(f"공통 인증 {field}는 {expected}이어야 합니다.")

    idempotency = _object(rules.get("idempotency"), "commonRules.idempotency", errors)
    if idempotency.get("header") != "Idempotency-Key":
        errors.append("공통 멱등성 header는 Idempotency-Key여야 합니다.")
    if not _exact_string_list(idempotency.get("requiredFor"), IDEMPOTENT_OPERATIONS):
        errors.append("멱등성 requiredFor는 create/compute/apply 정확 집합이어야 합니다.")
    if not _exact_string_list(idempotency.get("requires"), {
        "scope",
        "ttl",
        "replay",
        "payloadConflict",
        "concurrentRequest",
    }):
        errors.append("멱등성 scope/TTL/replay/payload conflict/동시 요청 계약이 필요합니다.")

    cursor = _object(rules.get("cursor"), "commonRules.cursor", errors)
    if cursor.get("cursor") != "opaque":
        errors.append("공통 cursor는 opaque여야 합니다.")
    if not _exact_string_list(
        cursor.get("requires"), {"size", "stableSort", "tieBreaker"}
    ):
        errors.append("cursor는 size, stable sort, tie-breaker를 모두 요구해야 합니다.")

    problem = _object(rules.get("problemDetails"), "commonRules.problemDetails", errors)
    if problem.get("mediaType") != "application/problem+json":
        errors.append("Problem Details mediaType은 application/problem+json이어야 합니다.")
    fields_value = problem.get("fields")
    forbidden_value = problem.get("forbiddenFields")
    fields = (
        set(fields_value)
        if isinstance(fields_value, list)
        and all(isinstance(item, str) for item in fields_value)
        else set()
    )
    forbidden = (
        set(forbidden_value)
        if isinstance(forbidden_value, list)
        and all(isinstance(item, str) for item in forbidden_value)
        else set()
    )
    if not _exact_string_list(fields_value, REQUIRED_PROBLEM_FIELDS):
        errors.append(
            "Problem Details 필드는 type,title,status,detail,instance,code,traceId,fieldErrors와 정확히 일치해야 합니다."
        )
    for name in sorted(FORBIDDEN_PROBLEM_FIELDS):
        if name in fields or name not in forbidden:
            errors.append(f"Problem Details에서 {name} 필드는 금지해야 합니다.")

    run = _object(rules.get("asyncRun"), "commonRules.asyncRun", errors)
    if run.get("states") != RUN_STATES:
        errors.append(
            "비동기 run canonical 상태는 queued/running/succeeded/failed/cancelled 순서와 값만 허용합니다."
        )
    fallback = run.get("fallback")
    if fallback != {"status": "succeeded", "result_source": "fallback"}:
        errors.append("fallback 성공은 status=succeeded, result_source=fallback이어야 합니다.")
    if run.get("candidateExpiryField") != "expiresAt":
        errors.append("candidate 만료는 run 상태와 분리된 expiresAt 필드여야 합니다.")
    if run.get("headers") != ["Location", "Retry-After"]:
        errors.append("비동기 접수는 Location과 Retry-After header 계약을 가져야 합니다.")
    if not _exact_string_list(run.get("failureObjectFields"), FAILURE_OBJECT_FIELDS):
        errors.append("비동기 failure object는 code/detail/retryable 정확 집합이어야 합니다.")
    if run.get("workerInput") != "immutable command snapshot":
        errors.append("비동기 workerInput은 immutable command snapshot이어야 합니다.")

    hashes = _object(rules.get("hashes"), "commonRules.hashes", errors)
    if hashes.get("commandInputHash") != "commandInputHash":
        errors.append("접수 hash 명칭은 commandInputHash여야 합니다.")
    if hashes.get("mcpInputHash") != "mcpInputHash":
        errors.append("MCP wire hash 명칭은 mcpInputHash여야 합니다.")
    if hashes.get("commandInputHash") == hashes.get("mcpInputHash"):
        errors.append("commandInputHash와 mcpInputHash는 분리해야 합니다.")


def _validate_ownership(ownership: Any, errors: list[str]) -> None:
    expected = {"durableCommandSchema": 108, "locationCleanup": 109, "workerRuntime": 74}
    if not isinstance(ownership, dict):
        errors.append("후속 구현 소유권(ownership)이 필요합니다.")
        return
    for name, issue in expected.items():
        if ownership.get(name) != issue:
            errors.append(f"{name} 구현 소유자는 #{issue}여야 합니다.")


def _validate_endpoints(endpoints: Any, contract_version: Any, errors: list[str]) -> None:
    if not isinstance(endpoints, list):
        errors.append("endpoints 배열이 필요합니다.")
        return

    identities: set[tuple[str, str]] = set()
    for index, endpoint in enumerate(endpoints):
        label = f"endpoints[{index}]"
        if not isinstance(endpoint, dict):
            errors.append(f"{label}는 객체여야 합니다.")
            continue
        endpoint_fields = set(endpoint)
        missing = REQUIRED_ENDPOINT_FIELDS - endpoint_fields
        for field in sorted(missing):
            errors.append(f"{label}에 필수 계약 필드 {field}가 없습니다.")
        for field in sorted(endpoint_fields - REQUIRED_ENDPOINT_FIELDS):
            errors.append(f"{label}에 허용되지 않은 계약 필드 {field}가 있습니다.")

        method = endpoint.get("method")
        path = endpoint.get("path")
        operation = endpoint.get("operation")
        if not isinstance(method, str) or method not in ALLOWED_METHODS:
            errors.append(f"{label}의 method는 GET/POST/PUT/PATCH/DELETE 중 하나여야 합니다.")
        if not isinstance(path, str) or not PATH_PATTERN.fullmatch(path):
            errors.append(f"{label}의 path는 /api/v1/... 형식이어야 합니다.")
        if not isinstance(operation, str) or operation not in ALLOWED_OPERATIONS:
            errors.append(f"{label}의 operation 분류가 허용되지 않습니다.")
        identity = (str(method), str(path))
        if identity in identities:
            errors.append(f"endpoint method/path 중복: {method} {path}")
        identities.add(identity)

        for field in ("owner", "presence", "dbOwner", "requestTimeCall", "dataLineage"):
            if not _non_empty(endpoint.get(field)):
                errors.append(f"{label}의 필수 계약 필드 {field}는 비어 있을 수 없습니다.")
        if endpoint.get("contractVersion") != contract_version:
            errors.append(f"{label}의 contract version이 공통 버전과 다릅니다.")

        _validate_endpoint_auth(endpoint.get("auth"), label, errors)
        _validate_endpoint_schemas(endpoint.get("schemas"), label, errors)
        _validate_endpoint_responses(endpoint.get("responses"), label, errors)
        _validate_endpoint_figma(endpoint.get("figma"), label, errors)
        _validate_endpoint_idempotency(endpoint.get("idempotency"), operation, label, errors)
        _validate_endpoint_pagination(endpoint.get("pagination"), label, errors)


def _validate_endpoint_auth(auth: Any, label: str, errors: list[str]) -> None:
    if not isinstance(auth, dict) or set(auth) != {"mode", "missingToken", "invalidToken"}:
        errors.append(f"{label}의 auth schema가 정확하지 않습니다.")
        return
    mode = auth.get("mode")
    if mode not in AUTH_MODES:
        errors.append(f"{label}의 인증 mode는 required 또는 optional이어야 합니다.")
    if mode == "required" and auth.get("missingToken") != 401:
        errors.append(f"{label}의 required 인증은 token 없음에 401이어야 합니다.")
    if mode == "optional" and auth.get("missingToken") != "anonymous":
        errors.append(f"{label}의 optional 인증은 token 없음에 anonymous여야 합니다.")
    if auth.get("invalidToken") != 401:
        errors.append(f"{label}은 invalid token에 401이어야 합니다.")


def _validate_endpoint_schemas(schemas: Any, label: str, errors: list[str]) -> None:
    if not _exact_non_empty_mapping(schemas, REQUIRED_SCHEMAS):
        errors.append(f"{label}은 non-empty path/query/header/body schema를 정확히 명시해야 합니다.")


def _validate_endpoint_responses(responses: Any, label: str, errors: list[str]) -> None:
    if not isinstance(responses, dict) or set(responses) != {"success", "errors"}:
        errors.append(f"{label}의 responses schema가 정확하지 않습니다.")
        return
    for kind in ("success", "errors"):
        codes = responses.get(kind)
        if not isinstance(codes, list) or not codes or not all(
            isinstance(code, int) and 100 <= code <= 599 for code in codes
        ):
            errors.append(f"{label}의 responses.{kind}는 HTTP status 배열이어야 합니다.")


def _validate_endpoint_figma(figma: Any, label: str, errors: list[str]) -> None:
    if not _exact_non_empty_mapping(figma, REQUIRED_FIGMA_FIELDS):
        errors.append(f"{label}은 non-empty Figma node/action/loading/empty/error를 명시해야 합니다.")


def _validate_endpoint_idempotency(
    idempotency: Any, operation: Any, label: str, errors: list[str]
) -> None:
    if operation in IDEMPOTENT_OPERATIONS:
        if not isinstance(idempotency, dict) or set(idempotency) != REQUIRED_IDEMPOTENCY_FIELDS:
            errors.append(
                f"{label}의 {operation} operation은 Idempotency-Key 멱등성 상세 계약 정확 집합이 필요합니다."
            )
            return
        if idempotency.get("required") is not True:
            errors.append(f"{label}의 {operation} operation은 Idempotency-Key가 필수입니다.")
        if idempotency.get("header") != "Idempotency-Key":
            errors.append(f"{label}의 변경 요청은 Idempotency-Key를 상속해야 합니다.")
        for field in REQUIRED_IDEMPOTENCY_FIELDS - {"required"}:
            if not _non_empty(idempotency.get(field)):
                errors.append(f"{label}의 멱등성 {field}는 비어 있을 수 없습니다.")
        return

    if idempotency != TEMPLATE_DEFAULTS["idempotency"]:
        errors.append(
            f"{label}의 비필수 Idempotency-Key 계약은 required=false, header=none이어야 합니다."
        )


def _validate_endpoint_pagination(pagination: Any, label: str, errors: list[str]) -> None:
    if pagination == TEMPLATE_DEFAULTS["pagination"]:
        return
    expected = {"type", "cursor", "size", "stableSort", "tieBreaker"}
    if not isinstance(pagination, dict) or set(pagination) != expected:
        errors.append(f"{label}의 cursor pagination schema가 정확하지 않습니다.")
        return
    if pagination.get("type") != "cursor" or pagination.get("cursor") != "opaque":
        errors.append(f"{label}의 cursor는 opaque여야 합니다.")
    size = pagination.get("size")
    if (
        not isinstance(size, dict)
        or set(size) != {"default", "max"}
        or not isinstance(size.get("default"), int)
        or not isinstance(size.get("max"), int)
        or not 0 < size["default"] <= size["max"]
    ):
        errors.append(f"{label}의 cursor size 정책이 잘못되었습니다.")
    if not _non_empty(pagination.get("stableSort")):
        errors.append(f"{label}의 stable sort가 필요합니다.")
    if not _non_empty(pagination.get("tieBreaker")):
        errors.append(f"{label}의 cursor tie-breaker가 필요합니다.")


def _validate_domains(
    domains: Any, contract_version: Any, template_id: Any, errors: list[str]
) -> None:
    if not isinstance(domains, list):
        errors.append("domainContracts 배열이 필요합니다.")
        return

    issue_values: list[int] = []
    for index, domain in enumerate(domains):
        if isinstance(domain, dict) and isinstance(domain.get("issue"), int):
            issue_values.append(domain["issue"])
        elif isinstance(domain, dict):
            errors.append(f"domainContracts[{index}]의 issue는 정수여야 합니다.")
    issue_counts = Counter(issue_values)
    found_issues = set(issue_counts)
    for issue in sorted(DOMAIN_ISSUES - found_issues):
        errors.append(f"도메인 계약 #{issue}가 catalog에 없습니다.")
    for issue in sorted(found_issues - DOMAIN_ISSUES):
        errors.append(f"범위 밖 도메인 계약 #{issue}가 catalog에 있습니다.")
    for issue, count in sorted(issue_counts.items()):
        if count != 1:
            errors.append(f"도메인 계약 #{issue}가 {count}회 중복되었습니다.")

    for domain in domains:
        if not isinstance(domain, dict):
            errors.append("domainContracts 항목은 객체여야 합니다.")
            continue
        issue = domain.get("issue", "?")
        if domain.get("inherits") != template_id:
            errors.append(f"도메인 계약 #{issue}는 공통 템플릿을 명시적으로 상속해야 합니다.")
        if not _non_empty(domain.get("domain")):
            errors.append(f"도메인 계약 #{issue}의 domain은 비어 있을 수 없습니다.")
        _validate_domain_versions(domain.get("versions"), issue, contract_version, errors)
        _validate_domain_readiness(
            domain.get("readiness"), domain.get("versions"), issue, errors
        )


def _validate_domain_versions(
    versions: Any, issue: Any, contract_version: Any, errors: list[str]
) -> None:
    if not isinstance(versions, dict) or set(versions) != {"local", "notion", "figma"}:
        errors.append(f"도메인 계약 #{issue}의 versions schema가 정확하지 않습니다.")
        return
    if versions.get("local") != contract_version:
        errors.append(f"도메인 계약 #{issue}의 local contract version이 다릅니다.")
    for source in ("notion", "figma"):
        version = versions.get(source)
        if version not in {contract_version, "not-linked"}:
            errors.append(
                f"도메인 계약 #{issue}의 Notion/Figma/local contract version drift: {source}={version}"
            )


def _validate_domain_readiness(
    readiness: Any, versions: Any, issue: Any, errors: list[str]
) -> None:
    if not isinstance(readiness, dict) or set(readiness) != set(READINESS_STAGES):
        errors.append(f"도메인 계약 #{issue}의 readiness는 세 단계 구조화 객체여야 합니다.")
        return

    statuses: dict[str, Any] = {}
    for stage in READINESS_STAGES:
        entry = readiness.get(stage)
        if not isinstance(entry, dict) or set(entry) != {"status", "evidence"}:
            stage_label = "Implementation Ready" if stage == "implementation" else stage
            errors.append(
                f"도메인 계약 #{issue}의 {stage_label} readiness는 status/evidence 구조화 객체여야 합니다."
            )
            statuses[stage] = None
            continue
        status = entry.get("status")
        evidence = entry.get("evidence")
        statuses[stage] = status
        if not isinstance(status, str) or status not in {"ready", "not-ready"}:
            errors.append(f"도메인 계약 #{issue}의 {stage} readiness status가 잘못되었습니다.")
        elif status == "not-ready" and evidence is not None:
            errors.append(f"도메인 계약 #{issue}의 not-ready {stage} evidence는 null이어야 합니다.")
        elif status == "ready" and not _exact_non_empty_mapping(
            evidence, READINESS_EVIDENCE_FIELDS[stage]
        ):
            label = "Implementation Ready" if stage == "implementation" else stage
            errors.append(f"도메인 계약 #{issue}의 {label} evidence는 구조화 정확 집합이어야 합니다.")

    if isinstance(versions, dict) and (
        versions.get("notion") == "not-linked" or versions.get("figma") == "not-linked"
    ) and any(status == "ready" for status in statuses.values()):
        errors.append(f"도메인 계약 #{issue}는 Notion/Figma not-linked 상태에서 승격할 수 없습니다.")
    if statuses.get("metadata") == "ready" and isinstance(versions, dict):
        version_values = (
            versions.get("local"),
            versions.get("notion"),
            versions.get("figma"),
        )
        if not all(isinstance(value, str) for value in version_values) or not (
            version_values[0] == version_values[1] == version_values[2]
        ):
            errors.append(
                f"도메인 계약 #{issue}는 Notion/Figma/local 버전 일치 전 Metadata Ready가 될 수 없습니다."
            )
    if statuses.get("example") == "ready" and statuses.get("metadata") != "ready":
        errors.append(f"도메인 계약 #{issue}의 Example Ready는 Metadata Ready 선행이 필요합니다.")
    if statuses.get("implementation") == "ready" and (
        statuses.get("metadata") != "ready" or statuses.get("example") != "ready"
    ):
        errors.append(
            f"도메인 계약 #{issue}의 Implementation Ready는 Metadata/Example Ready 선행이 필요합니다."
        )


def validate_template(
    template: dict[str, Any], catalog: dict[str, Any]
) -> list[str]:
    errors: list[str] = []
    if template.get("catalogVersion") != catalog.get("catalogVersion"):
        errors.append("endpoint template의 catalogVersion이 catalog와 다릅니다.")
    if template.get("templateId") != catalog.get("templateId"):
        errors.append("endpoint template의 templateId가 catalog와 다릅니다.")
    if template.get("contractVersion") != catalog.get("contractVersion"):
        errors.append("endpoint template의 contractVersion이 catalog와 다릅니다.")
    if not _exact_string_list(
        template.get("requiredEndpointFields"), REQUIRED_ENDPOINT_FIELDS
    ):
        errors.append("endpoint template의 필수 field 정확 집합이 다릅니다.")
    if not _exact_string_list(template.get("allowedMethods"), ALLOWED_METHODS):
        errors.append("endpoint template의 허용 method 정확 집합이 다릅니다.")
    if not _exact_string_list(template.get("allowedOperations"), ALLOWED_OPERATIONS):
        errors.append("endpoint template의 허용 operation 정확 집합이 다릅니다.")
    if template.get("defaults") != TEMPLATE_DEFAULTS:
        errors.append("endpoint template의 auth/idempotency/pagination default 상속이 다릅니다.")
    endpoint = template.get("endpoint")
    if not isinstance(endpoint, dict) or set(endpoint) != REQUIRED_ENDPOINT_FIELDS:
        errors.append("endpoint template 본문의 field 정확 집합이 다릅니다.")
    else:
        if endpoint.get("auth") != TEMPLATE_DEFAULTS["auth"]:
            errors.append("endpoint template의 auth default 상속이 다릅니다.")
        if endpoint.get("idempotency") != TEMPLATE_DEFAULTS["idempotency"]:
            errors.append("endpoint template의 idempotency default 상속이 다릅니다.")
        if endpoint.get("pagination") != TEMPLATE_DEFAULTS["pagination"]:
            errors.append("endpoint template의 pagination default 상속이 다릅니다.")
        _validate_endpoints([endpoint], catalog.get("contractVersion"), errors)
    return errors


def _read_json(path: Path, label: str) -> tuple[dict[str, Any] | None, list[str]]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return None, [f"REST 계약 {label}를 읽을 수 없습니다: {error}"]
    if not isinstance(value, dict):
        return None, [f"REST 계약 {label}는 JSON 객체여야 합니다."]
    return value, []


def validate_contract_files(catalog_path: Path, template_path: Path) -> list[str]:
    catalog, errors = _read_json(catalog_path, "catalog")
    template, template_errors = _read_json(template_path, "template")
    errors.extend(template_errors)
    if catalog is None:
        return errors
    errors.extend(validate_catalog(catalog))
    if template is not None:
        errors.extend(validate_template(template, catalog))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="REST 공통 계약 readiness 검사")
    parser.add_argument(
        "catalog",
        nargs="?",
        type=Path,
        default=Path("docs/contracts/rest/catalog.json"),
    )
    parser.add_argument(
        "--template",
        type=Path,
        default=Path("docs/contracts/rest/endpoint-template.json"),
    )
    args = parser.parse_args()

    errors = validate_contract_files(args.catalog, args.template)
    if errors:
        print("REST 계약 readiness 검사 실패:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"REST 계약 readiness 검사 성공: {args.catalog}, {args.template}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
