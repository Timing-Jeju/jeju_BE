#!/usr/bin/env python3
"""Issue #90 가능성 계산·이동 구간 계약을 fail-closed로 검사한다."""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import math
import re
import sys
import uuid
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/feasibility-legs/contract.json"
DEFAULT_CATALOG = ROOT / "docs/contracts/rest/catalog.json"
DEFAULT_FIXTURES = ROOT / "fixtures/contracts/feasibility-legs"
EXPECTED = {
    ("POST", "/api/v1/trips/{tripId}/feasibility-runs"): 55,
    ("GET", "/api/v1/trips/{tripId}/feasibility-runs/{runId}"): 97,
    ("GET", "/api/v1/trips/{tripId}/schedule-versions/{versionId}/legs/{legId}"): 56,
}
ALL_ENDPOINT_PATHS = [path for _, path in EXPECTED]
POST_ENDPOINT_PATH = "/api/v1/trips/{tripId}/feasibility-runs"
CANONICAL_ERRORS = [
    {"name": "invalidRequest", "code": "INVALID_REQUEST", "status": 400, "type": "https://api.timing-jeju.com/problems/invalid-request", "fixture": "400_invalid_request", "endpoints": ALL_ENDPOINT_PATHS, "condition": "malformed UUID/header/body or closed-schema violation"},
    {"name": "authenticationRequired", "code": "AUTHENTICATION_REQUIRED", "status": 401, "type": "https://api.timing-jeju.com/problems/authentication-required", "fixture": "401_authentication_required", "endpoints": ALL_ENDPOINT_PATHS, "condition": "missing or invalid bearer credentials"},
    {"name": "resourceNotFound", "code": "RESOURCE_NOT_FOUND", "status": 404, "type": "https://api.timing-jeju.com/problems/resource-not-found", "fixture": "404_resource_not_found", "endpoints": ALL_ENDPOINT_PATHS, "condition": "missing, wrong-owner, wrong-trip, wrong-version, or wrong-lineage resource"},
    {"name": "idempotencyKeyReused", "code": "IDEMPOTENCY_KEY_REUSED", "status": 409, "type": "https://api.timing-jeju.com/problems/idempotency-key-reused", "fixture": "409_idempotency_key_reused", "endpoints": [POST_ENDPOINT_PATH], "condition": "same key with a different canonical request hash"},
    {"name": "constraintViolation", "code": "FEASIBILITY_CONSTRAINT_VIOLATION", "status": 422, "type": "https://api.timing-jeju.com/problems/feasibility-constraint-violation", "fixture": "422_constraint_violation", "endpoints": [POST_ENDPOINT_PATH], "condition": "active schedule or duration/domain invariant is not satisfied"},
    {"name": "runLimited", "code": "FEASIBILITY_RUN_LIMITED", "status": 429, "type": "https://api.timing-jeju.com/problems/feasibility-run-limited", "fixture": "429_run_limited", "endpoints": [POST_ENDPOINT_PATH], "condition": "same active scope already has a run or external refresh quota is unavailable"},
    {"name": "dataUnavailable", "code": "FEASIBILITY_DATA_UNAVAILABLE", "status": 503, "type": "https://api.timing-jeju.com/problems/feasibility-data-unavailable", "fixture": "503_data_unavailable", "endpoints": ALL_ENDPOINT_PATHS, "condition": "required normalized facts or stored result cannot be read safely"},
]
READINESS = {
    "metadata": {"status": "not-ready", "evidence": None},
    "example": {"status": "not-ready", "evidence": None},
    "implementation": {"status": "not-ready", "evidence": None},
}
PROBLEM_FIELDS = {
    "type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"
}
CANONICAL_SCHEMAS_SHA256 = "bd6ebda615387efae8f4820455a16d7dfd97448d8eb3f76178a8530fc5cf3cf9"


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


def _mapping(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _parse_date_time(value: Any) -> datetime.datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else None


def _validate_schema_value(
    value: Any,
    schema: Any,
    schemas: dict[str, Any],
    path: str,
    errors: list[str],
) -> None:
    if not isinstance(schema, dict):
        errors.append(f"{path} schema object가 필요합니다.")
        return
    nullable = schema.get("nullable") is True
    if value is None:
        if not nullable:
            errors.append(f"{path} null을 허용하지 않습니다.")
        return
    if "$ref" in schema:
        target = schemas.get(schema.get("$ref"))
        if not isinstance(target, dict):
            errors.append(f"{path} 알 수 없는 schema reference입니다.")
            return
        _validate_schema_value(value, target, schemas, path, errors)
        return
    if "oneOf" in schema:
        choices = schema.get("oneOf")
        if not isinstance(choices, list) or not choices:
            errors.append(f"{path} oneOf가 비어 있습니다.")
            return
        matches = 0
        for choice in choices:
            candidate_errors: list[str] = []
            _validate_schema_value(value, choice, schemas, path, candidate_errors)
            if not candidate_errors:
                matches += 1
        if matches != 1:
            errors.append(f"{path} oneOf discriminator와 일치하지 않습니다.")
        return
    expected_type = schema.get("type")
    if expected_type == "object":
        if not isinstance(value, dict):
            errors.append(f"{path} object가 필요합니다.")
            return
        properties = schema.get("properties")
        required = schema.get("required")
        if not isinstance(properties, dict) or not isinstance(required, list) or any(not isinstance(item, str) for item in required):
            errors.append(f"{path} object schema required/properties가 잘못됐습니다.")
            return
        missing = [field for field in required if field not in value]
        if missing:
            errors.append(f"{path} required 필드가 누락됐습니다: {missing}")
        if schema.get("additionalProperties") is False:
            extra = set(value) - set(properties)
            if extra:
                errors.append(f"{path} 추가 필드를 허용하지 않습니다: {sorted(extra)}")
        for field, item in value.items():
            if field in properties:
                _validate_schema_value(item, properties[field], schemas, f"{path}.{field}", errors)
        return
    if expected_type == "array":
        if not isinstance(value, list):
            errors.append(f"{path} array가 필요합니다.")
            return
        if "minItems" in schema and len(value) < schema["minItems"]:
            errors.append(f"{path} minItems보다 짧습니다.")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            errors.append(f"{path} maxItems를 넘었습니다.")
        item_schema = schema.get("items")
        if not isinstance(item_schema, dict):
            errors.append(f"{path} items schema가 필요합니다.")
            return
        for index, item in enumerate(value):
            _validate_schema_value(item, item_schema, schemas, f"{path}[{index}]", errors)
        return
    if expected_type == "string":
        if not isinstance(value, str):
            errors.append(f"{path} string이 필요합니다.")
            return
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", sys.maxsize):
            errors.append(f"{path} 문자열 길이 범위를 벗어났습니다.")
        if "enum" in schema and value not in schema["enum"]:
            errors.append(f"{path} enum 값이 아닙니다.")
        pattern = schema.get("pattern")
        if pattern is not None:
            try:
                if not isinstance(pattern, str) or re.fullmatch(pattern, value) is None:
                    errors.append(f"{path} pattern과 일치하지 않습니다.")
            except re.error:
                errors.append(f"{path} schema pattern이 잘못됐습니다.")
        if schema.get("format") == "uuid":
            try:
                if str(uuid.UUID(value)) != value.lower():
                    raise ValueError
            except (ValueError, AttributeError):
                errors.append(f"{path} canonical UUID가 아닙니다.")
        elif schema.get("format") == "date-time" and _parse_date_time(value) is None:
            errors.append(f"{path} timezone date-time이 아닙니다.")
        return
    if expected_type == "boolean":
        if type(value) is not bool:
            errors.append(f"{path} boolean이 필요합니다.")
        return
    if expected_type == "integer":
        if type(value) is not int:
            errors.append(f"{path} integer가 필요합니다.")
            return
    elif expected_type == "number":
        if type(value) not in (int, float) or not math.isfinite(value):
            errors.append(f"{path} finite number가 필요합니다.")
            return
    else:
        errors.append(f"{path} 지원하지 않는 schema type입니다.")
        return
    if "minimum" in schema and value < schema["minimum"]:
        errors.append(f"{path} minimum보다 작습니다.")
    if "maximum" in schema and value > schema["maximum"]:
        errors.append(f"{path} maximum보다 큽니다.")


def _validate_schema_node(
    schema: Any, schemas: dict[str, Any], path: str, errors: list[str]
) -> None:
    if not isinstance(schema, dict):
        errors.append(f"{path} schema node는 object여야 합니다.")
        return
    if "$ref" in schema:
        if set(schema) not in ({"$ref"}, {"$ref", "nullable"}) or ("nullable" in schema and schema.get("nullable") not in (True, False)) or schema.get("$ref") not in schemas:
            errors.append(f"{path} $ref exact schema가 다릅니다.")
        return
    if "oneOf" in schema:
        choices = schema.get("oneOf")
        if set(schema) != {"oneOf", "nullable"} or schema.get("nullable") not in (True, False) or not isinstance(choices, list) or len(choices) < 2:
            errors.append(f"{path} oneOf exact schema가 다릅니다.")
            return
        for index, choice in enumerate(choices):
            _validate_schema_node(choice, schemas, f"{path}.oneOf[{index}]", errors)
        return
    schema_type = schema.get("type")
    if schema_type == "object":
        allowed = {"type", "nullable", "additionalProperties", "required", "properties"}
        required = schema.get("required")
        properties = schema.get("properties")
        if set(schema) != allowed or schema.get("nullable") not in (True, False) or schema.get("additionalProperties") is not False or not isinstance(required, list) or not isinstance(properties, dict) or required != list(properties):
            errors.append(f"{path} closed object meta-contract가 다릅니다.")
            return
        for name, child in properties.items():
            _validate_schema_node(child, schemas, f"{path}.properties.{name}", errors)
        return
    if schema_type == "string":
        allowed = {"type", "nullable", "format", "pattern", "enum", "minLength", "maxLength"}
        if set(schema) - allowed or schema.get("nullable") not in (True, False):
            errors.append(f"{path} string meta-contract가 다릅니다.")
            return
        if "format" in schema and schema["format"] not in {"uuid", "date-time"}:
            errors.append(f"{path} string format이 허용되지 않습니다.")
        if "pattern" in schema:
            try:
                re.compile(schema["pattern"])
            except (TypeError, re.error):
                errors.append(f"{path} string pattern이 잘못됐습니다.")
        if "enum" in schema and (not isinstance(schema["enum"], list) or not schema["enum"] or any(not isinstance(item, str) for item in schema["enum"])):
            errors.append(f"{path} string enum이 잘못됐습니다.")
        has_min = "minLength" in schema
        has_max = "maxLength" in schema
        if has_min != has_max or (has_min and (type(schema["minLength"]) is not int or type(schema["maxLength"]) is not int or not 0 <= schema["minLength"] <= schema["maxLength"])):
            errors.append(f"{path} string minLength/maxLength가 다릅니다.")
        if not any(key in schema for key in ("format", "pattern", "enum", "minLength")):
            errors.append(f"{path} string constraint가 필요합니다.")
        return
    if schema_type in {"integer", "number"}:
        if set(schema) != {"type", "nullable", "minimum", "maximum"} or schema.get("nullable") not in (True, False) or type(schema.get("minimum")) not in (int, float) or type(schema.get("maximum")) not in (int, float) or schema["minimum"] > schema["maximum"]:
            errors.append(f"{path} {schema_type} min/max meta-contract가 다릅니다.")
        return
    if schema_type == "boolean":
        if set(schema) != {"type", "nullable"} or schema.get("nullable") not in (True, False):
            errors.append(f"{path} boolean meta-contract가 다릅니다.")
        return
    if schema_type == "array":
        allowed = {"type", "nullable", "items", "minItems", "maxItems"}
        if set(schema) - allowed or schema.get("nullable") not in (True, False) or not isinstance(schema.get("items"), dict) or type(schema.get("maxItems")) is not int or schema.get("maxItems", -1) < 0:
            errors.append(f"{path} array items/maxItems meta-contract가 다릅니다.")
            return
        if "minItems" in schema and (type(schema["minItems"]) is not int or not 0 <= schema["minItems"] <= schema["maxItems"]):
            errors.append(f"{path} array minItems가 다릅니다.")
        _validate_schema_node(schema["items"], schemas, f"{path}.items", errors)
        return
    errors.append(f"{path} schema type이 누락되거나 지원되지 않습니다.")


def _validate_schema_definitions(schemas: Any, errors: list[str]) -> None:
    if not isinstance(schemas, dict):
        errors.append("schemas object가 필요합니다.")
        return
    expected = {
        "TripPath", "FeasibilityRunPath", "ScheduleLegPath", "FeasibilityRunHeaders",
        "FeasibilityRunRequest", "FeasibilityRunAccepted", "Failure", "RunProvenance",
        "SnapshotFreshness", "FeasibilityResult", "FeasibilityRunResponse", "LegEndpoint",
        "TransferStop", "Risk", "TransitRoute", "CarRoute", "WalkRoute", "LegDetailResponse",
        "ProblemDetails",
    }
    if set(schemas) != expected:
        errors.append("executable schema exact 집합이 다릅니다.")
    for name, schema in schemas.items():
        _validate_schema_node(schema, schemas, f"schemas.{name}", errors)


def _validate_canonical_schema_constraints(schemas: Any, errors: list[str]) -> None:
    if not isinstance(schemas, dict):
        errors.append("canonical schema definitions object가 필요합니다.")
        return
    canonical = json.dumps(
        schemas,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    if hashlib.sha256(canonical).hexdigest() != CANONICAL_SCHEMAS_SHA256:
        errors.append("canonical schema definitions immutable digest가 다릅니다.")


def _validate_accepted_fixture(accepted: Any, request_path: Any, errors: list[str]) -> None:
    if not isinstance(accepted, dict) or not isinstance(request_path, str):
        errors.append("202 fixture/request path object가 필요합니다.")
        return
    request_match = re.fullmatch(r"/api/v1/trips/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/feasibility-runs", request_path)
    headers = _mapping(accepted.get("headers"))
    body = _mapping(accepted.get("body"))
    retry_after = headers.get("Retry-After")
    run_id = body.get("runId")
    if request_match is None or not isinstance(run_id, str):
        errors.append("202 request tripId/body runId가 canonical UUID path가 아닙니다.")
        return
    expected_url = f"/api/v1/trips/{request_match.group(1)}/feasibility-runs/{run_id}"
    if accepted.get("status") != 202 or headers.get("Location") != expected_url or body.get("pollUrl") != expected_url:
        errors.append("202 Location/body.pollUrl/request tripId/body runId 관계가 다릅니다.")
    if not isinstance(retry_after, str) or re.fullmatch(r"(?:[1-9]|[1-5][0-9]|60)", retry_after) is None:
        errors.append("Retry-After는 1..60 canonical ASCII decimal이어야 합니다.")


def _validate_error_contract(contract: Any, problems: Any, errors: list[str]) -> None:
    conditions = _mapping(contract).get("errorConditions")
    if conditions != CANONICAL_ERRORS:
        errors.append("error name/code/status/type/fixture/applicability/condition canonical mapping이 다릅니다.")
        return
    if not isinstance(problems, dict):
        errors.append("problem fixture mapping이 필요합니다.")
        return
    for condition in CANONICAL_ERRORS:
        problem = problems.get(condition["fixture"])
        if not isinstance(problem, dict) or any(problem.get(field) != condition[field] for field in ("code", "status", "type")):
            errors.append(f"{condition['name']} problem fixture mapping이 다릅니다.")
    endpoint_errors = {
        endpoint.get("path"): set(_mapping(endpoint.get("responses")).get("errors", []))
        for endpoint in _mapping(contract).get("endpoints", [])
        if isinstance(endpoint, dict)
    } if isinstance(_mapping(contract).get("endpoints"), list) else {}
    for path in ALL_ENDPOINT_PATHS:
        applicable = {item["status"] for item in CANONICAL_ERRORS if path in item["endpoints"]}
        if endpoint_errors.get(path) != applicable:
            errors.append(f"{path} endpoint error applicability가 다릅니다.")


def _validate_freshness(snapshot: Any, response_time: Any, path: str, errors: list[str]) -> None:
    if not isinstance(snapshot, dict):
        errors.append(f"{path} freshness object가 필요합니다.")
        return
    observed = _parse_date_time(snapshot.get("observedAt"))
    expires = _parse_date_time(snapshot.get("expiresAt"))
    response = _parse_date_time(response_time)
    if observed is None or expires is None or response is None:
        errors.append(f"{path} freshness date-time이 잘못됐습니다.")
        return
    if observed > expires:
        errors.append(f"{path} observedAt은 expiresAt 이후일 수 없습니다.")
    if type(snapshot.get("stale")) is not bool or snapshot.get("stale") != (expires <= response):
        errors.append(f"{path} stale은 expiresAt <= responseTime과 같아야 합니다.")


def _validate_run_state(response: Any, path: str, errors: list[str]) -> None:
    if not isinstance(response, dict):
        errors.append(f"{path} run response object가 필요합니다.")
        return
    status = response.get("status")
    provenance = _mapping(response.get("provenance"))
    started = response.get("startedAt")
    facts = response.get("factsSnapshotAt")
    source = response.get("sourceDataVersion")
    result = response.get("result")
    failure = response.get("failure")
    mcp_hash = provenance.get("mcpInputHash")
    confidence = provenance.get("confidence")
    pre_mcp_terminal = (
        started is None and facts is None and source is None and mcp_hash is None
    )
    started_terminal = (
        started is not None and facts is not None and source is not None and mcp_hash is not None
    )
    exact = {
        "queued": (started is None and facts is None and source is None and mcp_hash is None and confidence is None and result is None and failure is None),
        "running": (started is not None and facts is not None and source is not None and confidence is None and result is None and failure is None),
        "succeeded": (started is not None and facts is not None and source is not None and mcp_hash is not None and confidence is not None and result is not None and failure is None),
        "failed": ((pre_mcp_terminal or started_terminal) and confidence is None and result is None and failure is not None),
        "cancelled": ((pre_mcp_terminal or started_terminal) and confidence is None and result is None and failure is not None),
    }
    if exact.get(status) is not True:
        errors.append(f"{path} {status} 상태의 presence/nullability가 다릅니다.")
    if status == "succeeded" and isinstance(result, dict):
        expires = _parse_date_time(result.get("expiresAt"))
        response_time = _parse_date_time(response.get("responseTime"))
        if expires is None or response_time is None or type(result.get("stale")) is not bool or result.get("stale") != (expires <= response_time):
            errors.append(f"{path} result stale은 expiresAt <= responseTime과 같아야 합니다.")
        for index, snapshot in enumerate(result.get("snapshots", [])):
            _validate_freshness(snapshot, response.get("responseTime"), f"{path}.result.snapshots[{index}]", errors)


def _validate_leg_semantics(leg: Any, path: str, errors: list[str]) -> None:
    if not isinstance(leg, dict):
        errors.append(f"{path} leg object가 필요합니다.")
        return
    components = [leg.get(name) for name in ("walkMinutes", "waitMinutes", "rideMinutes", "transferMinutes")]
    if any(type(value) is not int or value < 0 for value in components) or sum(components) != leg.get("totalMinutes"):
        errors.append(f"{path} duration component 합계가 다릅니다.")
    departure = _parse_date_time(leg.get("departureAt"))
    arrival = _parse_date_time(leg.get("arrivalAt"))
    if departure is None or arrival is None or (arrival - departure).total_seconds() != leg.get("totalMinutes", -1) * 60:
        errors.append(f"{path} departure/arrival elapsed 합계가 다릅니다.")
    mode = leg.get("transportMode")
    route = _mapping(leg.get("route"))
    if route.get("transportMode") != mode:
        errors.append(f"{path} route discriminator가 transportMode와 다릅니다.")
    endpoints = (_mapping(leg.get("from")), _mapping(leg.get("to")))
    transfers = leg.get("transferStops")
    endpoint_walks = [endpoint.get("walkMinutes") for endpoint in endpoints]
    if any(type(value) is not int or value < 0 for value in endpoint_walks) or sum(endpoint_walks) != leg.get("walkMinutes"):
        errors.append(f"{path} top-level walkMinutes는 from/to walkMinutes 합계여야 합니다.")
    if mode == "public_transit":
        if any(endpoint.get("stopId") is None or endpoint.get("stopName") is None for endpoint in endpoints):
            errors.append(f"{path} transit endpoint 정류장이 필요합니다.")
        if leg.get("remainingStops") is None:
            errors.append(f"{path} transit remainingStops가 필요합니다.")
        if not isinstance(transfers, list) or [item.get("sequence") for item in transfers if isinstance(item, dict)] != list(range(1, len(transfers) + 1)):
            errors.append(f"{path} transferStops 순서가 연속적이지 않습니다.")
        if route.get("departureStopId") != endpoints[0].get("stopId") or route.get("arrivalStopId") != endpoints[1].get("stopId"):
            errors.append(f"{path} route stop identity가 from/to와 다릅니다.")
        previous_departure = departure
        transfer_duration_seconds = 0.0
        if isinstance(transfers, list):
            for index, transfer in enumerate(transfers):
                if not isinstance(transfer, dict):
                    errors.append(f"{path}.transferStops[{index}] object가 필요합니다.")
                    continue
                transfer_arrival = _parse_date_time(transfer.get("arrivalAt"))
                transfer_departure = _parse_date_time(transfer.get("departureAt"))
                if departure is None or arrival is None or transfer_arrival is None or transfer_departure is None:
                    errors.append(f"{path}.transferStops[{index}] 시간이 잘못됐습니다.")
                    continue
                if not (departure <= transfer_arrival <= transfer_departure <= arrival):
                    errors.append(f"{path}.transferStops[{index}] leg window/arrival-departure 순서가 다릅니다.")
                if previous_departure is not None and transfer_arrival < previous_departure:
                    errors.append(f"{path}.transferStops[{index}] chronological order가 다릅니다.")
                transfer_duration_seconds += (transfer_departure - transfer_arrival).total_seconds()
                previous_departure = transfer_departure
        if transfer_duration_seconds != leg.get("transferMinutes", -1) * 60:
            errors.append(f"{path} transferMinutes는 각 환승 대기시간 합계여야 합니다.")
    elif mode in ("car", "walk"):
        if any(endpoint.get("stopId") is not None or endpoint.get("stopName") is not None for endpoint in endpoints):
            errors.append(f"{path} non-transit endpoint 정류장은 null이어야 합니다.")
        if transfers != [] or leg.get("remainingStops") is not None:
            errors.append(f"{path} non-transit transfer/remainingStops가 다릅니다.")
    else:
        errors.append(f"{path} transportMode가 다릅니다.")
    for index, snapshot in enumerate(leg.get("snapshots", [])):
        _validate_freshness(snapshot, leg.get("responseTime"), f"{path}.snapshots[{index}]", errors)


def _validate_contract(contract: dict[str, Any], errors: list[str]) -> None:
    if contract.get("schemaVersion") != "timing-jeju-feasibility-legs-contract/v1":
        errors.append("schemaVersion이 canonical v1이 아닙니다.")
    if contract.get("contractVersion") != "1.0.0" or contract.get("inherits") != "timing-jeju-rest-contract/v1":
        errors.append("공통 계약 상속/version이 다릅니다.")
    if contract.get("ownerIssue") != 90 or contract.get("implementationIssues") != [55, 97, 56]:
        errors.append("Issue #90/#55/#97/#56 소유 관계가 다릅니다.")

    endpoints = contract.get("endpoints")
    if not isinstance(endpoints, list):
        errors.append("endpoints 배열이 필요합니다.")
        return
    identities = [(item.get("method"), item.get("path")) for item in endpoints if isinstance(item, dict)]
    if set(identities) != set(EXPECTED) or len(identities) != 3:
        errors.append("세 endpoint method/path가 누락·중복됐습니다.")
    for endpoint in endpoints:
        if not isinstance(endpoint, dict):
            errors.append("endpoint는 object여야 합니다.")
            continue
        identity = (endpoint.get("method"), endpoint.get("path"))
        if endpoint.get("ownerIssue") != EXPECTED.get(identity):
            errors.append(f"{identity} 구현 owner Issue가 다릅니다.")
        if endpoint.get("auth") != {"mode": "required", "missingToken": 401, "invalidToken": 401}:
            errors.append(f"{identity} 인증 계약이 다릅니다.")
        if endpoint.get("owner") != "canonical JWT sub" or endpoint.get("crossOwnerStatus") != 404:
            errors.append(f"{identity} owner/404 은닉 계약이 다릅니다.")
        if endpoint.get("pagination") != {"type": "none"}:
            errors.append(f"{identity} pagination은 none이어야 합니다.")
    if endpoints and "409" not in _mapping(endpoints[0].get("idempotency")).get("payloadConflict", ""):
        errors.append("POST Idempotency-Key payload conflict 409가 필요합니다.")
    if endpoints and endpoints[0].get("successHeaders") != {
        "Location": "required concrete poll URL",
        "Retry-After": "required integer seconds 1..60",
    }:
        errors.append("POST 202 Location/Retry-After 계약이 다릅니다.")
    if any(not isinstance(item, dict) or item.get("idempotency") != {"required": False, "header": "none"} for item in endpoints[1:]):
        errors.append("GET은 Idempotency-Key를 사용하지 않습니다.")

    if _mapping(contract.get("runPolicy")).get("statuses") != ["queued", "running", "succeeded", "failed", "cancelled"]:
        errors.append("run 상태 enum이 다릅니다.")
    if _mapping(contract.get("provenancePolicy")).get("required") != ["algorithmVersion", "contractVersion", "commandInputHash", "mcpInputHash", "confidence"]:
        errors.append("계산 provenance 필드가 누락됐습니다.")
    freshness = _mapping(contract.get("freshnessPolicy"))
    if freshness.get("requiredPerSnapshot") != ["provider", "observedAt", "expiresAt", "stale"] or freshness.get("staleResultRepresentation") != "field":
        errors.append("snapshot freshness/stale 필드 계약이 다릅니다.")
    duration = _mapping(contract.get("durationPolicy"))
    if duration.get("components") != ["walkMinutes", "waitMinutes", "rideMinutes", "transferMinutes"] or duration.get("requireTotalsToMatch") is not True:
        errors.append("구간 시간 합계 불변식이 다릅니다.")
    if contract.get("readiness") != READINESS:
        errors.append("readiness는 외부 근거 전까지 not-ready여야 합니다.")
    if contract.get("externalTraceability") != {
        "notion": {"status": "not-linked", "url": None},
        "figma": {"status": "not-linked", "url": None},
    }:
        errors.append("Notion/Figma는 근거 전까지 not-linked여야 합니다.")
    compatibility = _mapping(contract.get("pathCompatibility"))
    canonical_paths = {path for _, path in EXPECTED}
    if compatibility.get("canonical") not in canonical_paths or "compatibility endpoint" not in compatibility.get("policy", ""):
        errors.append("#56 legacy path drift와 canonical migration 정책이 닫히지 않았습니다.")
    conditions = contract.get("errorConditions")
    if conditions != CANONICAL_ERRORS:
        errors.append("오류 canonical mapping이 다릅니다.")
    schemas = contract.get("schemas")
    _validate_schema_definitions(schemas, errors)
    _validate_canonical_schema_constraints(schemas, errors)
    provenance_properties = _mapping(_mapping(_mapping(schemas).get("RunProvenance")).get("properties"))
    for field in ("commandInputHash", "mcpInputHash"):
        if _mapping(provenance_properties.get(field)).get("pattern") != "^sha256:[0-9a-f]{64}$":
            errors.append(f"{field} canonical SHA-256 pattern이 다릅니다.")


def _validate_projection(contract: dict[str, Any], catalog: dict[str, Any], errors: list[str]) -> None:
    template = _load(ROOT / "docs/contracts/rest/endpoint-template.json")
    fields = template["requiredEndpointFields"]
    contract_endpoints = contract.get("endpoints")
    catalog_endpoints = catalog.get("endpoints")
    if not isinstance(contract_endpoints, list) or not isinstance(catalog_endpoints, list):
        errors.append("domain/catalog endpoints는 배열이어야 합니다.")
        return
    domain = [
        {key: endpoint.get(key) for key in fields}
        for endpoint in contract_endpoints if isinstance(endpoint, dict)
    ]
    projected = [
        endpoint for endpoint in catalog_endpoints if isinstance(endpoint, dict)
        if (endpoint.get("method"), endpoint.get("path")) in EXPECTED
    ]
    if domain != projected:
        errors.append("domain/catalog bidirectional endpoint projection이 다릅니다.")


def _validate_fixtures(contract: dict[str, Any], fixture_dir: Path, errors: list[str]) -> None:
    fixtures: dict[str, Any] = {}
    for name in ("request", "success", "problem"):
        path = fixture_dir / f"{name}.json"
        if not path.is_file():
            errors.append(f"{name} fixture가 없습니다.")
            continue
        try:
            fixtures[name] = _load(path)
        except (OSError, ValueError) as exc:
            errors.append(f"{name} fixture JSON 오류: {exc}")
            continue
        if not isinstance(fixtures[name], dict):
            errors.append(f"{name} fixture root는 object여야 합니다.")
            continue
        if fixtures[name].get("contractVersion") != contract.get("contractVersion"):
            errors.append(f"{name} fixture contractVersion이 다릅니다.")
    if "request" in fixtures:
        request_examples = _mapping(fixtures["request"].get("examples"))
        identities = {(item.get("method"), item.get("path", "").replace("50000000-0000-0000-0000-000000000001", "{tripId}").replace("63000000-0000-0000-0000-000000000001", "{runId}").replace("60000000-0000-0000-0000-000000000001", "{versionId}").replace("62000000-0000-0000-0000-000000000002", "{legId}")) for item in request_examples.values() if isinstance(item, dict)}
        if identities != set(EXPECTED):
            errors.append("request fixture가 세 endpoint를 exact하게 소유하지 않습니다.")
        schemas = _mapping(contract.get("schemas"))
        intake = _mapping(request_examples.get("intake"))
        _validate_schema_value(intake.get("headers"), {"$ref": "FeasibilityRunHeaders", "nullable": False}, schemas, "request.intake.headers", errors)
        _validate_schema_value(intake.get("body"), {"$ref": "FeasibilityRunRequest", "nullable": False}, schemas, "request.intake.body", errors)
    if "success" in fixtures:
        success = _mapping(fixtures["success"].get("examples"))
        schemas = _mapping(contract.get("schemas"))
        accepted = _mapping(success.get("accepted"))
        headers = _mapping(accepted.get("headers"))
        try:
            retry_after = int(headers.get("Retry-After", ""))
        except (TypeError, ValueError):
            retry_after = 0
        if set(headers) != {"Location", "Retry-After"} or not isinstance(headers.get("Location"), str) or not headers["Location"].startswith("/api/v1/") or not 1 <= retry_after <= 60:
            errors.append("202 Location/Retry-After fixture가 다릅니다.")
        request_path = _mapping(_mapping(_mapping(fixtures.get("request")).get("examples")).get("intake")).get("path")
        _validate_accepted_fixture(accepted, request_path, errors)
        _validate_schema_value(accepted.get("body"), {"$ref": "FeasibilityRunAccepted", "nullable": False}, schemas, "success.accepted.body", errors)
        states = _mapping(success.get("runStates"))
        if list(states) != ["queued", "running", "succeeded", "failed", "cancelled"]:
            errors.append("success fixture run 상태 matrix가 다릅니다.")
        for status, response in states.items():
            _validate_schema_value(response, {"$ref": "FeasibilityRunResponse", "nullable": False}, schemas, f"success.runStates.{status}", errors)
            _validate_run_state(response, f"success.runStates.{status}", errors)
        variants = _mapping(success.get("terminalVariants"))
        if list(variants) != ["failedPreMcp", "cancelledPreMcp", "failedStarted", "cancelledStarted"]:
            errors.append("terminal phase variant exact 집합이 다릅니다.")
        for name, response in variants.items():
            _validate_schema_value(response, {"$ref": "FeasibilityRunResponse", "nullable": False}, schemas, f"success.terminalVariants.{name}", errors)
            _validate_run_state(response, f"success.terminalVariants.{name}", errors)
        modes = _mapping(success.get("legModes"))
        if list(modes) != ["public_transit", "car", "walk"]:
            errors.append("transit/car/walk fixture exact 집합이 다릅니다.")
        for mode, leg in modes.items():
            _validate_schema_value(leg, {"$ref": "LegDetailResponse", "nullable": False}, schemas, f"success.legModes.{mode}", errors)
            _validate_leg_semantics(leg, f"success.legModes.{mode}", errors)
    if "problem" in fixtures:
        examples = _mapping(fixtures["problem"].get("examples"))
        if {item.get("status") for item in examples.values() if isinstance(item, dict)} != {400, 401, 404, 409, 422, 429, 503}:
            errors.append("problem fixture status matrix가 다릅니다.")
        for name, problem in examples.items():
            _validate_schema_value(
                problem,
                {"$ref": "ProblemDetails", "nullable": False},
                _mapping(contract.get("schemas")),
                f"problem.{name}",
                errors,
            )
            if not isinstance(problem, dict) or set(problem) != PROBLEM_FIELDS or problem.get("instance") != f"urn:timing-jeju:problem:{problem.get('traceId')}":
                errors.append(f"{name} Problem Details shape/traceId가 다릅니다.")
        _validate_error_contract(contract, examples, errors)
    forbidden = ("api_key", "apikey", "authorization:", "rawproviderpayload", "providermessage")
    for name, fixture in fixtures.items():
        rendered = json.dumps(fixture, ensure_ascii=False).lower()
        if any(token in rendered for token in forbidden):
            errors.append(f"{name} fixture에 비밀/원문 필드가 있습니다.")


def validate(contract_path: Path, catalog_path: Path, fixture_dir: Path) -> list[str]:
    errors: list[str] = []
    try:
        contract = _load(contract_path)
        catalog = _load(catalog_path)
    except (OSError, ValueError) as exc:
        return [f"계약 JSON을 읽을 수 없습니다: {exc}"]
    if not isinstance(contract, dict) or not isinstance(catalog, dict):
        return ["계약과 catalog는 JSON object여야 합니다."]
    try:
        _validate_contract(contract, errors)
        _validate_projection(contract, catalog, errors)
        _validate_fixtures(contract, fixture_dir, errors)
    except (AttributeError, KeyError, TypeError, ValueError):
        errors.append("중첩 계약 값의 타입 또는 구조가 올바르지 않습니다.")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURES)
    args = parser.parse_args()
    errors = validate(args.contract, args.catalog, args.fixtures)
    if errors:
        for error in errors:
            print(f"[feasibility-legs contract] {error}", file=sys.stderr)
        return 1
    print("가능성 계산·이동 구간 계약 검증 성공")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
