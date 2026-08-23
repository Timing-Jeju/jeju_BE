#!/usr/bin/env python3
"""Issue #94 날씨 예보 공개 API 계약을 fail-closed로 검사한다."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/weather-forecast/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
TEMPLATE = ROOT / "docs/contracts/rest/endpoint-template.json"
FIXTURES = ROOT / "fixtures/contracts/weather-forecast"
IDENTITY = ("GET", "/api/v1/weather/forecast")
PROBLEM_FIELDS = {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"}
FIXTURE_TOP_FIELDS = {
    "request": {"contractVersion", "method", "path", "headers", "query", "body"},
    "success": {"contractVersion", "status", "evaluatedAt", "body"},
    "problem": {"contractVersion", "examples"},
}
TOP_FIELDS = {
    "schemaVersion", "contractVersion", "sourceSpecVersion", "inherits", "ownerIssue",
    "implementationIssues", "schemas", "endpoints", "gridPolicy", "forecastPolicy",
    "categoryPolicy", "freshnessPolicy", "securityPolicy", "errorConditions",
    "externalTraceability", "readiness", "schemaGap",
}
ENDPOINT_FIELDS = {
    "method", "path", "operation", "requestSchema", "headersSchema", "successSchema",
    "auth", "owner", "presence", "responses", "errorMatrix", "idempotency", "pagination",
    "dbOwner", "requestTimeCall", "dataLineage", "figma", "contractVersion",
}
EXPECTED_ERROR_MATRIX = {
    "400": ["INVALID_WEATHER_FORECAST_QUERY"],
    "401": ["INVALID_ACCESS_TOKEN"],
    "422": ["WEATHER_LOCATION_NOT_SUPPORTED", "WEATHER_FORECAST_HORIZON_NOT_SUPPORTED"],
    "503": ["WEATHER_FORECAST_UNAVAILABLE"],
}
AUTHORITATIVE_NOTION_LINK = {
    "url": "https://app.notion.com/p/3a40a87c7ce5816ba8f7ed2027e94b8c",
    "pageId": "3a40a87c-7ce5-816b-a8f7-ed2027e94b8c",
}
AUTHORITATIVE_FIGMA_LINK = {
    "url": "https://www.figma.com/design/4mKep38zm17iupVSQVsSJW?node-id=1291-8816",
    "fileKey": "4mKep38zm17iupVSQVsSJW",
    "nodeId": "1291:8816",
}
AUTHORITATIVE_DECISION = "https://github.com/Timing-Jeju/jeju_BE/issues/94#issuecomment-5387038123"


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


def _expected_policies() -> dict[str, Any]:
    canonical = _load(DEFAULT_CONTRACT)
    return {key: canonical[key] for key in ("gridPolicy", "forecastPolicy", "categoryPolicy", "freshnessPolicy", "securityPolicy")}


def _validate_identity(contract: dict[str, Any], errors: list[str]) -> None:
    if set(contract) != TOP_FIELDS:
        errors.append("top-level field exact 집합이 다릅니다.")
    expected = {
        "schemaVersion": "timing-jeju-weather-forecast-contract/v1",
        "contractVersion": "1.0.0",
        "sourceSpecVersion": "1.0.0",
        "inherits": "timing-jeju-rest-contract/v1",
        "ownerIssue": 94,
        "implementationIssues": [67],
    }
    for key, value in expected.items():
        if contract.get(key) != value:
            errors.append(f"identity {key}가 다릅니다.")


def _validate_query(contract: dict[str, Any], errors: list[str]) -> None:
    schemas = contract.get("schemas")
    if not isinstance(schemas, dict) or set(schemas) != {"WeatherForecastQuery", "CommonHeaders", "WeatherGrid", "WeatherForecastResponse"}:
        errors.append("query/response schema exact 집합이 다릅니다.")
        return
    query = schemas["WeatherForecastQuery"]
    if query.get("type") != "object" or query.get("nullable") is not False or query.get("additionalProperties") is not False:
        errors.append("query closed object 경계가 다릅니다.")
    if query.get("required") != ["lat", "lng", "dateTime"] or set(query.get("properties", {})) != {"lat", "lng", "dateTime"}:
        errors.append("query lat/lng/dateTime simultaneous required 경계가 다릅니다.")
    lat = query.get("properties", {}).get("lat", {})
    lng = query.get("properties", {}).get("lng", {})
    date_time = query.get("properties", {}).get("dateTime", {})
    if (lat.get("type"), lat.get("exclusiveMinimum"), lat.get("exclusiveMaximum"), lat.get("finite"), lat.get("nullable")) != ("number", -90, 90, True, False):
        errors.append("query lat range/type가 다릅니다.")
    if (lng.get("type"), lng.get("minimum"), lng.get("maximum"), lng.get("finite"), lng.get("nullable")) != ("number", -180, 180, True, False):
        errors.append("query lng range/type가 다릅니다.")
    if date_time != {"type": "string", "nullable": False, "format": "date-time", "timezone": "Asia/Seoul", "requiredOffset": "+09:00", "seconds": 0}:
        errors.append("query dateTime timezone/format/granularity가 다릅니다.")
    canonical_schemas = _load(DEFAULT_CONTRACT)["schemas"]
    if schemas.get("CommonHeaders") != canonical_schemas["CommonHeaders"] or schemas.get("WeatherGrid") != canonical_schemas["WeatherGrid"] or schemas.get("WeatherForecastResponse") != canonical_schemas["WeatherForecastResponse"]:
        errors.append("response schema canonical closed contract가 다릅니다.")


def _validate_endpoint(contract: dict[str, Any], errors: list[str]) -> None:
    endpoints = contract.get("endpoints")
    if not isinstance(endpoints, list) or len(endpoints) != 1 or not isinstance(endpoints[0], dict):
        errors.append("endpoint method/path duplicate 또는 exact 집합이 다릅니다.")
        return
    endpoint = endpoints[0]
    if set(endpoint) != ENDPOINT_FIELDS:
        errors.append("endpoint field exact 집합이 다릅니다.")
    if (endpoint.get("method"), endpoint.get("path")) != IDENTITY or endpoint.get("operation") != "read":
        errors.append("endpoint method/path/operation이 다릅니다.")
    if endpoint.get("requestSchema") != "WeatherForecastQuery" or endpoint.get("headersSchema") != "CommonHeaders" or endpoint.get("successSchema") != "WeatherForecastResponse":
        errors.append("endpoint schema refs가 다릅니다.")
    if endpoint.get("auth") != {"mode": "optional", "missingToken": "anonymous", "invalidToken": 401}:
        errors.append("endpoint auth가 #72 optional 계약과 다릅니다.")
    if endpoint.get("owner") != "none; public weather fact has no user owner":
        errors.append("endpoint owner 계약이 다릅니다.")
    if endpoint.get("responses") != {"success": [200], "errors": [400, 401, 422, 503]} or endpoint.get("errorMatrix") != EXPECTED_ERROR_MATRIX:
        errors.append("endpoint response/error matrix가 다릅니다.")
    if endpoint.get("pagination") != {"type": "none"}:
        errors.append("endpoint cursor/pagination은 none이어야 합니다.")
    if endpoint.get("idempotency") != {"required": False, "header": "none"}:
        errors.append("endpoint GET idempotency 계약이 다릅니다.")
    if endpoint.get("contractVersion") != contract.get("contractVersion"):
        errors.append("endpoint/local contract version drift가 있습니다.")
    figma = endpoint.get("figma")
    if figma != {"node": "1291:8816", "action": "1291:8819", "loading": "1291:8820", "empty": "1291:8822", "error": "1291:8823"}:
        errors.append("endpoint Figma contract/state node 근거가 다릅니다.")
    if endpoint != _load(DEFAULT_CONTRACT)["endpoints"][0]:
        errors.append("endpoint canonical contract가 다릅니다.")


def _validate_policies(contract: dict[str, Any], errors: list[str]) -> None:
    for key, expected in _expected_policies().items():
        if contract.get(key) != expected:
            label = {
                "gridPolicy": "grid",
                "forecastPolicy": "horizon/base/version/storage projection",
                "categoryPolicy": "category nullable/omitted",
                "freshnessPolicy": "fallback/freshness",
                "securityPolicy": "security",
            }[key]
            errors.append(f"{label} policy가 canonical 계약과 다릅니다.")
    if contract.get("schemaGap") != _load(DEFAULT_CONTRACT)["schemaGap"]:
        errors.append("schemaGap exact 계약이 다릅니다.")


def _validate_problems(contract: dict[str, Any], errors: list[str]) -> None:
    conditions = contract.get("errorConditions")
    if not isinstance(conditions, list) or len(conditions) != 5:
        errors.append("problem condition exact 집합이 다릅니다.")
        return
    canonical = {item["code"]: item for item in _load(DEFAULT_CONTRACT)["errorConditions"]}
    actual = {item.get("code"): item for item in conditions if isinstance(item, dict)}
    if set(actual) != set(canonical) or len(actual) != len(conditions):
        errors.append("problem code duplicate/누락이 있습니다.")
    for code, expected in canonical.items():
        item = actual.get(code)
        if item != expected:
            errors.append(f"problem {code} canonical condition/example이 다릅니다.")
            continue
        example = item["example"]
        if set(example) != PROBLEM_FIELDS:
            errors.append(f"problem {code} 8-field exact 계약이 다릅니다.")
        if not re.search(r"[가-힣]", example["title"] + example["detail"]):
            errors.append(f"problem {code} title/detail은 한국어여야 합니다.")


def _validate_external(contract: dict[str, Any], errors: list[str]) -> None:
    external = contract.get("externalTraceability")
    if not isinstance(external, dict) or set(external) != {"notion", "figma"}:
        errors.append("external readiness source가 다릅니다.")
        return
    canonical = _load(DEFAULT_CONTRACT)["externalTraceability"]
    if external != canonical:
        errors.append("external readiness/evidence/owner follow-up exact 계약이 다릅니다.")
    notion = external.get("notion", {})
    notion_evidence = notion.get("evidence", {})
    readiness = contract.get("readiness", {})
    metadata_evidence = readiness.get("metadata", {}).get("evidence", {}) if isinstance(readiness, dict) else {}
    if (
        notion.get("status") != "ready"
        or notion.get("contractVersion") != "1.0.0"
        or notion_evidence.get("specStatus") != "Ready"
        or notion_evidence.get("screen") != "장소 상세 / 일정 날씨 · Figma 1291:8816"
        or notion_evidence.get("alignedScope") != ["response", "errors", "fallback", "security"]
        or notion_evidence.get("decisionComment") != AUTHORITATIVE_DECISION
        or notion.get("ownerFollowUp") is not None
    ):
        errors.append("external readiness notion exact aligned 근거가 다릅니다.")
    external_notion_link = {
        "url": notion_evidence.get("pageUrl"),
        "pageId": notion_evidence.get("pageId"),
    }
    readiness_notion_link = metadata_evidence.get("notionPage") if isinstance(metadata_evidence, dict) else None
    if external_notion_link != AUTHORITATIVE_NOTION_LINK or readiness_notion_link != AUTHORITATIVE_NOTION_LINK:
        errors.append("external/readiness Notion authoritative lineage가 다릅니다.")
    figma = external.get("figma", {})
    figma_evidence = figma.get("evidence", {})
    if (
        figma.get("status") != "ready"
        or figma.get("contractVersion") != "1.0.0"
        or figma_evidence.get("fileKey") != "4mKep38zm17iupVSQVsSJW"
        or [figma_evidence.get(field) for field in ("contractNode", "actionNode", "loadingNode", "successNode", "emptyNode", "errorNode")]
        != ["1291:8816", "1291:8819", "1291:8820", "1291:8821", "1291:8822", "1291:8823"]
        or figma_evidence.get("decisionComment") != AUTHORITATIVE_DECISION
        or figma.get("ownerFollowUp") is not None
    ):
        errors.append("external readiness figma exact field/state linkage가 다릅니다.")
    external_figma_link = {
        "url": AUTHORITATIVE_FIGMA_LINK["url"],
        "fileKey": figma_evidence.get("fileKey"),
        "nodeId": figma_evidence.get("contractNode"),
    }
    readiness_figma_link = metadata_evidence.get("figmaNode") if isinstance(metadata_evidence, dict) else None
    if external_figma_link != AUTHORITATIVE_FIGMA_LINK or readiness_figma_link != AUTHORITATIVE_FIGMA_LINK:
        errors.append("external/readiness Figma authoritative lineage가 다릅니다.")
    expected_readiness = _load(DEFAULT_CONTRACT)["readiness"]
    if contract.get("readiness") != expected_readiness:
        errors.append("external metadata/example ready와 implementation not-ready 경계가 다릅니다.")


def catalog_projection(endpoint: dict[str, Any]) -> dict[str, Any]:
    return {
        "method": endpoint["method"],
        "path": endpoint["path"],
        "operation": endpoint["operation"],
        "auth": endpoint["auth"],
        "owner": "Spring Weather domain; public non-owner read; contract #94, implementation #67",
        "schemas": {"path": "none", "query": endpoint["requestSchema"], "headers": endpoint["headersSchema"], "body": "none"},
        "presence": endpoint["presence"],
        "responses": endpoint["responses"],
        "dbOwner": endpoint["dbOwner"],
        "requestTimeCall": endpoint["requestTimeCall"],
        "dataLineage": endpoint["dataLineage"],
        "figma": endpoint["figma"],
        "contractVersion": endpoint["contractVersion"],
        "idempotency": endpoint["idempotency"],
        "pagination": endpoint["pagination"],
    }


def _validate_projection(contract: dict[str, Any], errors: list[str]) -> None:
    try:
        catalog = _load(CATALOG)
        template = _load(TEMPLATE)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        errors.append(f"catalog/template을 읽을 수 없습니다: {exc}")
        return
    matches = [item for item in catalog.get("endpoints", []) if (item.get("method"), item.get("path")) == IDENTITY]
    if matches != [catalog_projection(contract["endpoints"][0])]:
        errors.append("catalog endpoint projection이 canonical 계약과 다릅니다.")
    domain = next((item for item in catalog.get("domainContracts", []) if item.get("issue") == 94), None)
    expected_domain = {
        "issue": 94,
        "domain": "weather-forecast",
        "inherits": "timing-jeju-rest-contract/v1",
        "versions": {"local": "1.0.0", "notion": "1.0.0", "figma": "1.0.0"},
        "readiness": contract["readiness"],
    }
    if domain != expected_domain:
        errors.append("catalog domain readiness/version projection이 다릅니다.")
    projection = catalog_projection(contract["endpoints"][0])
    if set(projection) != set(template.get("requiredEndpointFields", [])) or template.get("templateId") != contract.get("inherits"):
        errors.append("template weather forecast projection이 다릅니다.")


def _parse_kst(value: Any, path: str, errors: list[str]) -> datetime | None:
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+09:00", value):
        errors.append(f"{path} date-time +09:00 형식이 아닙니다.")
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        errors.append(f"{path} date-time 값이 유효하지 않습니다.")
        return None


def _validate_value(value: Any, schema: dict[str, Any], schemas: dict[str, Any], path: str, errors: list[str]) -> None:
    if "$ref" in schema:
        target = schemas.get(schema["$ref"])
        if not isinstance(target, dict):
            errors.append(f"{path} schema ref가 없습니다.")
            return
        _validate_value(value, target, schemas, path, errors)
        return
    if value is None:
        if schema.get("nullable") is not True:
            errors.append(f"{path} nullable 위반입니다.")
        return
    expected_type = schema.get("type")
    valid_type = {
        "object": lambda item: isinstance(item, dict),
        "string": lambda item: isinstance(item, str),
        "number": lambda item: isinstance(item, (int, float)) and not isinstance(item, bool) and math.isfinite(item),
        "integer": lambda item: isinstance(item, int) and not isinstance(item, bool),
        "boolean": lambda item: isinstance(item, bool),
    }.get(expected_type, lambda item: True)
    if not valid_type(value):
        errors.append(f"{path} schema type 위반입니다.")
        return
    if "const" in schema and value != schema["const"]:
        errors.append(f"{path} schema const 위반입니다.")
    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path} schema enum 위반입니다.")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            errors.append(f"{path} schema minimum 위반입니다.")
        if "maximum" in schema and value > schema["maximum"]:
            errors.append(f"{path} schema maximum 위반입니다.")
        if "exclusiveMinimum" in schema and value <= schema["exclusiveMinimum"]:
            errors.append(f"{path} schema exclusiveMinimum 위반입니다.")
        if "exclusiveMaximum" in schema and value >= schema["exclusiveMaximum"]:
            errors.append(f"{path} schema exclusiveMaximum 위반입니다.")
    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append(f"{path} schema minLength 위반입니다.")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            errors.append(f"{path} schema maxLength 위반입니다.")
        if "pattern" in schema and re.fullmatch(schema["pattern"], value) is None:
            errors.append(f"{path} schema pattern 위반입니다.")
        if schema.get("format") == "date-time":
            parsed = _parse_kst(value, path, errors)
            if parsed is not None and schema.get("offset") == "+09:00" and parsed.utcoffset().total_seconds() != 32400:
                errors.append(f"{path} schema offset 위반입니다.")
        if schema.get("format") == "date":
            try:
                datetime.strptime(value, "%Y-%m-%d")
            except ValueError:
                errors.append(f"{path} schema date 위반입니다.")
    if isinstance(value, dict):
        properties = schema.get("properties", {})
        required = set(schema.get("required", []))
        missing = required - set(value)
        if missing:
            errors.append(f"{path} schema required 누락: {sorted(missing)}")
        if schema.get("additionalProperties") is False and set(value) - set(properties):
            errors.append(f"{path} schema additionalProperties 위반입니다.")
        for key in set(value) & set(properties):
            _validate_value(value[key], properties[key], schemas, f"{path}.{key}", errors)


def validate_fixtures(contract: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    try:
        request = _load(FIXTURES / "request.json")
        success = _load(FIXTURES / "success.json")
        problems = _load(FIXTURES / "problem.json")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"fixture를 읽을 수 없습니다: {exc}"]
    for label, fixture in (("request", request), ("success", success), ("problem", problems)):
        if not isinstance(fixture, dict) or set(fixture) != FIXTURE_TOP_FIELDS[label]:
            errors.append(f"{label} fixture top-level exact 필드가 다릅니다.")
            continue
        if fixture.get("contractVersion") != contract.get("contractVersion"):
            errors.append(f"{label} fixture contractVersion이 다릅니다.")
    if not all(isinstance(fixture, dict) for fixture in (request, success, problems)):
        return errors
    _validate_value(request.get("headers"), contract["schemas"]["CommonHeaders"], contract["schemas"], "request.headers", errors)
    query = request.get("query")
    _validate_value(query, contract["schemas"]["WeatherForecastQuery"], contract["schemas"], "request.query", errors)
    if request.get("method") != "GET" or request.get("path") != "/api/v1/weather/forecast" or request.get("body") is not None:
        errors.append("request fixture method/path/body가 다릅니다.")
    requested = _parse_kst(query.get("dateTime") if isinstance(query, dict) else None, "request.query.dateTime", errors)
    if requested is not None and (requested.second != 0 or requested.microsecond != 0):
        errors.append("request.query.dateTime seconds는 00이어야 합니다.")
    if success.get("status") != 200:
        errors.append("success fixture status는 200이어야 합니다.")
    body = success.get("body")
    _validate_value(body, contract["schemas"]["WeatherForecastResponse"], contract["schemas"], "success.body", errors)
    if isinstance(body, dict):
        valid_at = _parse_kst(body.get("validAt"), "success.body.validAt", errors)
        observed = _parse_kst(body.get("observedAt"), "success.body.observedAt", errors)
        expires = _parse_kst(body.get("expiresAt"), "success.body.expiresAt", errors)
        evaluated = _parse_kst(success.get("evaluatedAt"), "success.evaluatedAt", errors)
        if requested is not None and valid_at != requested:
            errors.append("fixture validAt은 requested dateTime과 정확히 같아야 합니다.")
        if observed is not None and expires is not None and observed >= expires:
            errors.append("fixture observedAt/expiresAt 순서가 잘못됐습니다.")
        if evaluated is not None and expires is not None:
            expected_stale = evaluated >= expires
            if body.get("fallbackUsed") is True:
                expected_stale = True
            if body.get("stale") is not expected_stale:
                errors.append("fixture stale/fallback 의미가 다릅니다.")
    expected_examples = {item["code"]: item["example"] for item in contract["errorConditions"]}
    if problems.get("examples") != expected_examples:
        errors.append("problem fixture condition→8-field example linkage가 다릅니다.")
    return errors


def validate(contract: Any, skip_catalog_fixtures: bool = False) -> list[str]:
    if not isinstance(contract, dict):
        return ["contract root는 object여야 합니다."]
    errors: list[str] = []
    _validate_identity(contract, errors)
    _validate_query(contract, errors)
    _validate_endpoint(contract, errors)
    _validate_policies(contract, errors)
    _validate_problems(contract, errors)
    _validate_external(contract, errors)
    if not skip_catalog_fixtures:
        _validate_projection(contract, errors)
        errors.extend(validate_fixtures(contract))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="날씨 예보 API 계약 검사")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--skip-catalog-fixtures", action="store_true")
    args = parser.parse_args()
    try:
        contract = _load(args.contract)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"날씨 예보 API 계약 검사 실패: 계약 JSON을 읽을 수 없습니다: {exc}", file=sys.stderr)
        return 1
    errors = validate(contract, args.skip_catalog_fixtures)
    if errors:
        print("날씨 예보 API 계약 검사 실패:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"날씨 예보 API 계약 검사 성공: {args.contract}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
