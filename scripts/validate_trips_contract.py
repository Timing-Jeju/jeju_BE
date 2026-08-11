#!/usr/bin/env python3
"""Issue #85 여행 CRUD machine contract와 fixture를 엄격히 검사한다."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import unicodedata
import uuid
from datetime import date, datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CONTRACT_RELATIVE = Path("docs/contracts/domains/trips/contract.json")
CATALOG_RELATIVE = Path("docs/contracts/rest/catalog.json")
FIXTURE_ROOT_RELATIVE = Path("fixtures/contracts/trips")
INITIAL_MIGRATION_RELATIVE = Path(
    "supabase/migrations/20260728000000_initial_public_schema.sql"
)
CANONICAL_CONTRACT_SHA256 = "9ff86a1a626123d7687632079568136917a3c330e94847326b479ded3fb2525a"
CANONICAL_CATALOG_SHA256 = "5566971d9898ba7abbc1aebb858468a558878781a88dde251e42b7441b71885b"
CONTRACT_FIELDS = {
    "schemaVersion",
    "contractVersion",
    "sourceSpecVersion",
    "inherits",
    "ownerIssue",
    "implementationIssues",
    "schemas",
    "endpoints",
    "pagination",
    "tripPolicy",
    "scorePolicy",
    "createSemantics",
    "patchSemantics",
    "deleteSemantics",
    "ownership",
    "storage",
    "externalTraceability",
    "readiness",
}
EXPECTED_ENDPOINT_IDENTITIES = [
    ("GET", "/api/v1/trips"),
    ("POST", "/api/v1/trips"),
    ("GET", "/api/v1/trips/{tripId}"),
    ("PATCH", "/api/v1/trips/{tripId}"),
    ("DELETE", "/api/v1/trips/{tripId}"),
]
EXPECTED_PROBLEMS = {
    "400_invalid_query_parameter": (400, "INVALID_QUERY_PARAMETER", "invalid-query-parameter"),
    "400_invalid_cursor": (400, "INVALID_CURSOR", "invalid-cursor"),
    "400_cursor_context_mismatch": (400, "CURSOR_CONTEXT_MISMATCH", "cursor-context-mismatch"),
    "400_invalid_request": (400, "INVALID_REQUEST", "invalid-request"),
    "400_if_match_required": (400, "IF_MATCH_REQUIRED", "if-match-required"),
    "400_invalid_if_match": (400, "INVALID_IF_MATCH", "invalid-if-match"),
    "401_authentication_required": (401, "AUTHENTICATION_REQUIRED", "authentication-required"),
    "401_invalid_access_token": (401, "INVALID_ACCESS_TOKEN", "invalid-access-token"),
    "404_trip_not_found": (404, "TRIP_NOT_FOUND", "trip-not-found"),
    "409_idempotency_payload_conflict": (409, "IDEMPOTENCY_PAYLOAD_CONFLICT", "idempotency-payload-conflict"),
    "409_idempotency_request_in_progress": (409, "IDEMPOTENCY_REQUEST_IN_PROGRESS", "idempotency-request-in-progress"),
    "409_trip_version_conflict": (409, "TRIP_VERSION_CONFLICT", "trip-version-conflict"),
    "409_trip_regeneration_required": (409, "TRIP_REGENERATION_REQUIRED", "trip-regeneration-required"),
    "409_trip_terminal_state_conflict": (409, "TRIP_TERMINAL_STATE_CONFLICT", "trip-terminal-state-conflict"),
    "409_trip_delete_conflict": (409, "TRIP_DELETE_CONFLICT", "trip-delete-conflict"),
    "422_trip_constraint_violation": (422, "TRIP_CONSTRAINT_VIOLATION", "trip-constraint-violation"),
}
RFC3339 = re.compile(
    r"^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(?:\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$"
)
TRACE_ID = re.compile(r"^[0-9a-f]{32}$")


class ContractValidationError(ValueError):
    pass


def _reject_constant(value: str) -> None:
    raise ContractValidationError(f"JSON 표준 밖 숫자 {value}는 허용되지 않습니다.")


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ContractValidationError(f"중복 JSON key {key}는 허용되지 않습니다.")
        value[key] = item
    return value


def _load_json(path: Path) -> Any:
    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            parse_constant=_reject_constant,
            object_pairs_hook=_reject_duplicate_pairs,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ContractValidationError) as exc:
        raise ContractValidationError(f"{path}: strict JSON을 읽을 수 없습니다: {exc}") from exc


def _canonical_digest(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _catalog_projection(catalog: dict[str, Any]) -> dict[str, Any]:
    domain = [
        item
        for item in catalog.get("domainContracts", [])
        if isinstance(item, dict) and item.get("domain") == "trips"
    ]
    endpoints = [
        endpoint
        for endpoint in catalog.get("endpoints", [])
        if isinstance(endpoint, dict)
        and endpoint.get("path") in {"/api/v1/trips", "/api/v1/trips/{tripId}"}
    ]
    return {"domainContracts": domain, "endpoints": endpoints}


def validate(root: Path) -> list[str]:
    try:
        contract = _load_json(root / CONTRACT_RELATIVE)
        catalog = _load_json(root / CATALOG_RELATIVE)
        request = _load_json(root / FIXTURE_ROOT_RELATIVE / "request.json")
        success = _load_json(root / FIXTURE_ROOT_RELATIVE / "success.json")
        problems = _load_json(root / FIXTURE_ROOT_RELATIVE / "problem.json")
    except ContractValidationError as exc:
        return [str(exc)]

    errors: list[str] = []
    if not isinstance(contract, dict):
        return ["여행 contract 최상위는 object여야 합니다."]
    unknown = set(contract) - CONTRACT_FIELDS
    missing = CONTRACT_FIELDS - set(contract)
    if unknown:
        errors.append("여행 contract에 허용되지 않은 필드가 있습니다: " + ", ".join(sorted(unknown)))
    if missing:
        errors.append("여행 contract 필수 필드가 없습니다: " + ", ".join(sorted(missing)))
    if _canonical_digest(contract) != CANONICAL_CONTRACT_SHA256:
        errors.append("여행 contract canonical schema 또는 endpoint 의미가 변경되었습니다.")
    if not isinstance(catalog, dict):
        errors.append("REST catalog는 object여야 합니다.")
    elif _canonical_digest(_catalog_projection(catalog)) != CANONICAL_CATALOG_SHA256:
        errors.append("여행 catalog canonical endpoint/readiness가 변경되었습니다.")

    schemas = contract.get("schemas")
    if not isinstance(schemas, dict):
        return errors + ["여행 schemas는 object여야 합니다."]
    _validate_canonical_semantics(contract, errors)
    _validate_request_fixture(request, schemas, errors)
    _validate_success_fixture(success, schemas, errors)
    _validate_problem_fixture(problems, schemas, errors)
    _validate_external_readiness(contract, errors)
    _validate_schema_drift(root, contract, errors)
    return errors


def _validate_canonical_semantics(contract: dict[str, Any], errors: list[str]) -> None:
    identities = [
        (item.get("method"), item.get("path"))
        for item in contract.get("endpoints", [])
        if isinstance(item, dict)
    ]
    if identities != EXPECTED_ENDPOINT_IDENTITIES:
        errors.append("여행 endpoint method/path canonical 순서가 다릅니다.")
    if contract.get("schemaVersion") != "timing-jeju-trips-contract/v1":
        errors.append("여행 schemaVersion canonical 값이 다릅니다.")
    if (
        contract.get("contractVersion") != "1.0.0"
        or contract.get("sourceSpecVersion") != "v1.1"
        or contract.get("inherits") != "timing-jeju-rest-contract/v1"
        or contract.get("ownerIssue") != 85
        or contract.get("implementationIssues") != [44, 45]
    ):
        errors.append("여행 version/Issue canonical mapping이 다릅니다.")

    endpoints = contract.get("endpoints", [])
    if len(endpoints) == 5:
        if endpoints[0].get("pagination") != {"type": "cursor", "defaultSize": 20, "maxSize": 100}:
            errors.append("여행 list pagination canonical 계약이 다릅니다.")
        create_idempotency = endpoints[1].get("idempotency", {})
        if not isinstance(create_idempotency, dict) or (
            create_idempotency.get("required") is not True
            or create_idempotency.get("header") != "Idempotency-Key"
            or create_idempotency.get("ttl") != "24h"
        ):
            errors.append("여행 POST idempotency canonical 계약이 다릅니다.")
        if endpoints[3].get("headersSchema") != "PatchTripHeaders":
            errors.append("여행 PATCH If-Match canonical 계약이 다릅니다.")

    ownership = contract.get("ownership", {})
    if ownership != {
        "source": "canonical JWT sub",
        "lookup": "query by tripId and userId together",
        "crossOwnerConcealment": 404,
        "forbiddenInputs": ["email", "user_metadata", "provider profile", "raw JWT", "session_id", "public_token"],
    }:
        errors.append("여행 ownership semantic canonical 계약이 다릅니다.")

    policy = contract.get("tripPolicy", {})
    if (
        policy.get("minimumDays") != 1
        or policy.get("maximumDays") != 30
        or policy.get("timezone") != "Asia/Seoul"
        or policy.get("timezoneFormat") != "IANA"
    ):
        errors.append("여행 date/timezone semantic canonical 계약이 다릅니다.")

    patch = contract.get("patchSemantics", {})
    expected_matrix = {
        "title": "maintain",
        "userPace": "invalidate-and-require-regeneration",
        "transportModes": "invalidate-and-require-regeneration",
        "startDate": "reject-with-regeneration-required-when-any-schedule-version-exists",
        "endDate": "reject-with-regeneration-required-when-any-schedule-version-exists",
        "timezone": "reject-with-regeneration-required-when-any-schedule-version-exists",
    }
    if (
        patch.get("omitted") != "preserve"
        or patch.get("null") != "reject"
        or patch.get("collections") != "replace"
        or patch.get("scheduleEffectMatrix") != expected_matrix
    ):
        errors.append("여행 PATCH semantic canonical 계약이 다릅니다.")

    deletion = contract.get("deleteSemantics", {})
    if (
        deletion.get("repeat") != "404 TRIP_NOT_FOUND"
        or deletion.get("crossOwner") != "404 TRIP_NOT_FOUND"
        or deletion.get("tripAggregate") != "cascade"
        or deletion.get("locationAndExecutionHistory") != "delete-with-aggregate"
        or deletion.get("externalImportLineage") != "preserve"
        or deletion.get("userAndAuthIdentity") != "preserve"
    ):
        errors.append("여행 DELETE semantic canonical 계약이 다릅니다.")


def _validate_request_fixture(fixture: Any, schemas: dict[str, Any], errors: list[str]) -> None:
    label = "request fixture"
    expected_names = {
        "contractVersion",
        "list",
        "create",
        "detail",
        "patchMaintain",
        "patchInvalidate",
        "patchDatesWithoutSchedule",
        "delete",
    }
    if not isinstance(fixture, dict) or set(fixture) != expected_names:
        errors.append(f"{label} 최상위 구조가 정확하지 않습니다.")
        return
    if fixture.get("contractVersion") != "1.0.0":
        errors.append(f"{label} contractVersion이 다릅니다.")
    identities = {
        "list": ("GET", "/api/v1/trips"),
        "create": ("POST", "/api/v1/trips"),
        "detail": ("GET", "/api/v1/trips/50000000-0000-0000-0000-000000000001"),
        "patchMaintain": ("PATCH", "/api/v1/trips/50000000-0000-0000-0000-000000000001"),
        "patchInvalidate": ("PATCH", "/api/v1/trips/50000000-0000-0000-0000-000000000001"),
        "patchDatesWithoutSchedule": ("PATCH", "/api/v1/trips/50000000-0000-0000-0000-000000000002"),
        "delete": ("DELETE", "/api/v1/trips/50000000-0000-0000-0000-000000000001"),
    }
    expected_fields = {
        "list": {"method", "path", "headers", "query"},
        "create": {"method", "path", "headers", "body"},
        "detail": {"method", "path", "pathParameters", "headers"},
        "patchMaintain": {"method", "path", "pathParameters", "headers", "body"},
        "patchInvalidate": {"method", "path", "pathParameters", "headers", "body"},
        "patchDatesWithoutSchedule": {"method", "path", "pathParameters", "headers", "body"},
        "delete": {"method", "path", "pathParameters", "headers"},
    }
    for name, identity in identities.items():
        value = fixture.get(name)
        if not isinstance(value, dict):
            errors.append(f"{label}.{name}은 object여야 합니다.")
            continue
        if set(value) != expected_fields[name]:
            errors.append(f"{label}.{name} HTTP envelope 필드가 정확하지 않습니다.")
        if (value.get("method"), value.get("path")) != identity:
            errors.append(f"{label}.{name} method/path가 canonical 계약과 다릅니다.")
        headers = value.get("headers")
        if not isinstance(headers, dict) or headers.get("Authorization") != "Bearer <fixture-access-token>":
            errors.append(f"{label}.{name} Authorization fixture가 정확하지 않습니다.")

    _validate_value(fixture["list"].get("query"), schemas.get("TripsListQuery"), schemas, f"{label}.list.query", errors)
    _validate_value(fixture["create"].get("body"), schemas.get("CreateTripRequest"), schemas, f"{label}.create.body", errors)
    create_headers = fixture["create"].get("headers")
    if not isinstance(create_headers, dict) or set(create_headers) != {"Authorization", "Idempotency-Key"}:
        errors.append(f"{label}.create headers가 정확하지 않습니다.")
    else:
        _validate_value({"Idempotency-Key": create_headers["Idempotency-Key"]}, schemas.get("CreateTripHeaders"), schemas, f"{label}.create.headers", errors)

    for name in ("detail", "patchMaintain", "patchInvalidate", "patchDatesWithoutSchedule", "delete"):
        value = fixture[name]
        parameters = value.get("pathParameters")
        _validate_value(parameters, schemas.get("TripPath"), schemas, f"{label}.{name}.pathParameters", errors)
        trip_id = parameters.get("tripId") if isinstance(parameters, dict) else None
        if not isinstance(trip_id, str) or value.get("path") != f"/api/v1/trips/{trip_id}":
            errors.append(f"{label}.{name} path와 tripId가 일치하지 않습니다.")

    for name in ("patchMaintain", "patchInvalidate", "patchDatesWithoutSchedule"):
        value = fixture[name]
        headers = value.get("headers")
        if not isinstance(headers, dict) or set(headers) != {"Authorization", "If-Match"}:
            errors.append(f"{label}.{name} headers가 정확하지 않습니다.")
        else:
            _validate_value({"If-Match": headers["If-Match"]}, schemas.get("PatchTripHeaders"), schemas, f"{label}.{name}.headers", errors)
        _validate_value(value.get("body"), schemas.get("PatchTripRequest"), schemas, f"{label}.{name}.body", errors)

    for name in ("detail", "delete"):
        headers = fixture[name].get("headers")
        if not isinstance(headers, dict) or set(headers) != {"Authorization"}:
            errors.append(f"{label}.{name} headers는 Authorization만 가져야 합니다.")

    _validate_trip_request_semantic(fixture["create"].get("body"), f"{label}.create.body", errors)
    _validate_trip_request_semantic(fixture["patchDatesWithoutSchedule"].get("body"), f"{label}.patchDatesWithoutSchedule.body", errors)
    for name in ("create", "patchInvalidate"):
        body = fixture[name].get("body")
        if isinstance(body, dict) and "transportModes" in body:
            _validate_transport_modes(body.get("transportModes"), f"{label}.{name}.body.transportModes", errors)


def _validate_success_fixture(fixture: Any, schemas: dict[str, Any], errors: list[str]) -> None:
    label = "success fixture"
    expected_names = {
        "list",
        "create",
        "createReplay",
        "detail",
        "patchMaintain",
        "patchInvalidate",
        "patchDatesWithoutSchedule",
        "delete",
    }
    if not isinstance(fixture, dict) or set(fixture) != expected_names:
        errors.append(f"{label} 최상위 구조가 정확하지 않습니다.")
        return
    list_response = fixture.get("list")
    if not isinstance(list_response, dict) or set(list_response) != {"status", "headers", "responseTime", "body"}:
        errors.append(f"{label}.list HTTP envelope 필드가 정확하지 않습니다.")
    else:
        if list_response.get("status") != 200 or list_response.get("headers") != {"Content-Type": "application/json"}:
            errors.append(f"{label}.list status/headers가 canonical 계약과 다릅니다.")
        _validate_value(list_response.get("body"), schemas.get("TripsListResponse"), schemas, f"{label}.list.body", errors)
        _validate_datetime(list_response.get("responseTime"), f"{label}.list.responseTime", errors)

    create = fixture.get("create")
    expected_create_headers = {
        "Content-Type": "application/json",
        "Location": "/api/v1/trips/50000000-0000-0000-0000-000000000001",
        "ETag": '"trip-fixture-r1"',
        "Idempotency-Replayed": "false",
    }
    if not isinstance(create, dict) or set(create) != {"status", "headers", "body"}:
        errors.append(f"{label}.create HTTP envelope 필드가 정확하지 않습니다.")
    else:
        if create.get("status") != 201 or create.get("headers") != expected_create_headers:
            errors.append(f"{label}.create status/headers가 canonical 계약과 다릅니다.")
        _validate_value(create.get("body"), schemas.get("TripDetail"), schemas, f"{label}.create.body", errors)
    replay = fixture.get("createReplay")
    replay_headers = dict(expected_create_headers)
    replay_headers["Idempotency-Replayed"] = "true"
    if replay != {"status": 201, "headers": replay_headers, "bodyRef": "create.body"}:
        errors.append(f"{label}.createReplay는 원본 응답과 replay header를 보존해야 합니다.")

    detail = fixture.get("detail")
    if not isinstance(detail, dict) or set(detail) != {"status", "headers", "responseTime", "body"}:
        errors.append(f"{label}.detail HTTP envelope 필드가 정확하지 않습니다.")
    else:
        if detail.get("status") != 200 or detail.get("headers") != {"Content-Type": "application/json", "ETag": '"trip-fixture-r7"'}:
            errors.append(f"{label}.detail status/headers가 canonical 계약과 다릅니다.")
        _validate_value(detail.get("body"), schemas.get("TripDetail"), schemas, f"{label}.detail.body", errors)
        _validate_datetime(detail.get("responseTime"), f"{label}.detail.responseTime", errors)

    expected_patch_etags = {
        "patchMaintain": '"trip-fixture-r8"',
        "patchInvalidate": '"trip-fixture-r9"',
        "patchDatesWithoutSchedule": '"trip-fixture-r2"',
    }
    for name, etag in expected_patch_etags.items():
        response = fixture.get(name)
        if not isinstance(response, dict) or set(response) != {"status", "headers", "body"}:
            errors.append(f"{label}.{name} HTTP envelope 필드가 정확하지 않습니다.")
            continue
        if response.get("status") != 200 or response.get("headers") != {"Content-Type": "application/json", "ETag": etag}:
            errors.append(f"{label}.{name} status/ETag가 정확하지 않습니다.")
        _validate_value(response.get("body"), schemas.get("TripDetail"), schemas, f"{label}.{name}.body", errors)
    if fixture.get("delete") != {"status": 204, "headers": {}, "body": None}:
        errors.append(f"{label}.delete는 204, 빈 headers, null body여야 합니다.")

    for name in ("create", "detail", "patchMaintain", "patchInvalidate", "patchDatesWithoutSchedule"):
        response = fixture.get(name)
        if isinstance(response, dict):
            _validate_trip_detail_semantic(response.get("body"), f"{label}.{name}.body", errors)

    if isinstance(list_response, dict):
        body = list_response.get("body")
        if isinstance(body, dict):
            _validate_page_semantic(body.get("page"), errors)
            items = body.get("items")
            if isinstance(items, list):
                for index, item in enumerate(items):
                    _validate_trip_summary_semantic(item, list_response.get("responseTime"), f"{label}.list.items[{index}]", errors)
                sortable = [
                    (item.get("updatedAt"), item.get("tripId"))
                    for item in items
                    if isinstance(item, dict)
                ]
                if sortable != sorted(sortable, reverse=True):
                    errors.append("pagination semantic: list items가 updatedAt DESC, tripId DESC가 아닙니다.")

    if isinstance(detail, dict):
        _validate_score_semantic(detail.get("body"), detail.get("responseTime"), f"{label}.detail.body", errors)
    if isinstance(fixture.get("patchMaintain"), dict):
        body = fixture["patchMaintain"].get("body")
        if not isinstance(body, dict) or body.get("scheduleEffect") != "maintained" or body.get("regenerationRequired") is not False or body.get("activeScheduleVersionId") is None:
            errors.append(f"{label}.patchMaintain schedule effect가 정확하지 않습니다.")
    if isinstance(fixture.get("patchInvalidate"), dict):
        body = fixture["patchInvalidate"].get("body")
        if not isinstance(body, dict) or body.get("scheduleEffect") != "invalidated" or body.get("regenerationRequired") is not True or body.get("activeScheduleVersionId") is not None or body.get("status") != "draft":
            errors.append(f"{label}.patchInvalidate schedule effect가 정확하지 않습니다.")
    if isinstance(fixture.get("patchDatesWithoutSchedule"), dict):
        body = fixture["patchDatesWithoutSchedule"].get("body")
        if not isinstance(body, dict) or body.get("scheduleEffect") != "none" or body.get("regenerationRequired") is not False:
            errors.append(f"{label}.patchDatesWithoutSchedule schedule effect가 정확하지 않습니다.")


def _validate_problem_fixture(fixture: Any, schemas: dict[str, Any], errors: list[str]) -> None:
    label = "problem fixture"
    if not isinstance(fixture, dict) or set(fixture) != set(EXPECTED_PROBLEMS):
        errors.append(f"{label} condition key 집합이 canonical 계약과 다릅니다.")
        return
    for key, (status, code, slug) in EXPECTED_PROBLEMS.items():
        value = fixture.get(key)
        _validate_value(value, schemas.get("ProblemDetails"), schemas, f"{label}.{key}", errors)
        expected_type = f"https://api.timing-jeju.com/problems/{slug}"
        if not isinstance(value, dict) or (value.get("status"), value.get("code"), value.get("type")) != (status, code, expected_type):
            errors.append(f"{label}.{key} status/code/type이 canonical 조건과 다릅니다.")
        if isinstance(value, dict) and value.get("instance") != f"urn:timing-jeju:problem:{value.get('traceId')}":
            errors.append(f"{label}.{key} instance와 traceId가 일치하지 않습니다.")


def _validate_trip_request_semantic(value: Any, label: str, errors: list[str]) -> None:
    if not isinstance(value, dict) or not {"startDate", "endDate"} <= set(value):
        return
    try:
        start = date.fromisoformat(value["startDate"])
        end = date.fromisoformat(value["endDate"])
    except (TypeError, ValueError):
        errors.append(f"request fixture semantic: {label} 날짜가 유효하지 않습니다.")
        return
    days = (end - start).days + 1
    if days < 1 or days > 30:
        errors.append(f"request fixture semantic: {label} 여행 기간은 1..30일이어야 합니다.")
    if "timezone" in value and value.get("timezone") != "Asia/Seoul":
        errors.append(f"request fixture semantic: {label}.timezone은 Asia/Seoul이어야 합니다.")


def _validate_transport_modes(value: Any, label: str, errors: list[str]) -> None:
    if not isinstance(value, list) or not value:
        return
    modes = [item.get("mode") for item in value if isinstance(item, dict)]
    priorities = [item.get("priority") for item in value if isinstance(item, dict)]
    primary = [item for item in value if isinstance(item, dict) and item.get("primary") is True]
    if len(modes) != len(set(modes)) or priorities != list(range(1, len(value) + 1)) or len(primary) != 1 or primary[0].get("priority") != 1:
        errors.append(f"request fixture semantic: {label} mode/priority/primary가 일관되지 않습니다.")


def _validate_trip_detail_semantic(value: Any, label: str, errors: list[str]) -> None:
    if not isinstance(value, dict):
        return
    _validate_transport_modes(value.get("transportModes"), f"{label}.transportModes", errors)
    try:
        start = date.fromisoformat(value.get("startDate"))
        end = date.fromisoformat(value.get("endDate"))
    except (TypeError, ValueError):
        return
    expected_dates = [date.fromordinal(start.toordinal() + offset).isoformat() for offset in range((end - start).days + 1)]
    days = value.get("days")
    if not isinstance(days, list) or [item.get("date") for item in days if isinstance(item, dict)] != expected_dates or [item.get("dayNo") for item in days if isinstance(item, dict)] != list(range(1, len(expected_dates) + 1)):
        errors.append(f"success fixture semantic: {label}.days가 날짜 범위와 일치하지 않습니다.")
    active = value.get("activeScheduleVersionId")
    status = value.get("status")
    if status in {"planned", "live", "completed"} and active is None:
        errors.append(f"success fixture semantic: {label} status={status}이면 active schedule이 필요합니다.")
    _validate_score_semantic(value, None, label, errors)


def _validate_trip_summary_semantic(value: Any, response_time: Any, label: str, errors: list[str]) -> None:
    if not isinstance(value, dict):
        return
    try:
        created = _parse_datetime(value.get("createdAt"))
        updated = _parse_datetime(value.get("updatedAt"))
    except ValueError:
        return
    if updated < created:
        errors.append(f"pagination semantic: {label}.updatedAt이 createdAt보다 빠릅니다.")
    _validate_score_semantic(value, response_time, label, errors)


def _validate_score_semantic(value: Any, response_time: Any, label: str, errors: list[str]) -> None:
    if not isinstance(value, dict):
        return
    score = value.get("totalScore")
    provenance = value.get("scoreProvenance")
    if (score is None) != (provenance is None):
        errors.append(f"score semantic: {label} totalScore와 scoreProvenance null 상태가 다릅니다.")
        return
    if provenance is None or not isinstance(provenance, dict):
        return
    if provenance.get("source") != "feasibility_run" or provenance.get("scheduleVersionId") != value.get("activeScheduleVersionId"):
        errors.append(f"score semantic: {label} source/active schedule provenance가 다릅니다.")
    try:
        observed = _parse_datetime(provenance.get("observedAt"))
        calculated = _parse_datetime(provenance.get("calculatedAt"))
        expires = _parse_datetime(provenance.get("expiresAt"))
        response = _parse_datetime(response_time) if response_time is not None else None
    except ValueError:
        return
    if not observed <= calculated <= expires:
        errors.append(f"score semantic: {label} freshness 시각 순서가 잘못되었습니다.")
    if response is not None and provenance.get("stale") is not (response >= expires):
        errors.append(f"score semantic: {label} stale 값이 responseTime/expiresAt과 다릅니다.")


def _validate_page_semantic(value: Any, errors: list[str]) -> None:
    if not isinstance(value, dict):
        return
    if value.get("hasNext") is True and not isinstance(value.get("nextCursor"), str):
        errors.append("pagination semantic: hasNext=true이면 nextCursor가 필요합니다.")
    if value.get("hasNext") is False and value.get("nextCursor") is not None:
        errors.append("pagination semantic: hasNext=false이면 nextCursor는 null이어야 합니다.")


def _validate_external_readiness(contract: dict[str, Any], errors: list[str]) -> None:
    external = contract.get("externalTraceability")
    readiness = contract.get("readiness")
    if not isinstance(external, dict) or not isinstance(readiness, dict):
        return
    notion = external.get("notion")
    figma = external.get("figma")
    if not isinstance(notion, dict) or notion.get("status") != "not-ready" or notion.get("sourceSpecVersion") != "v1.1" or notion.get("canonicalVersion") != "1.0.0" or not isinstance(notion.get("pages"), list) or len(notion["pages"]) != 5:
        errors.append("external readiness: Notion source/canonical evidence가 정확하지 않습니다.")
    if not isinstance(figma, dict) or figma.get("status") != "not-ready" or figma.get("fileKey") != "4mKep38zm17iupVSQVsSJW" or figma.get("pageNodeId") != "251:4347" or figma.get("loading") != "not-observed" or figma.get("empty") != "not-observed" or figma.get("error") != "not-observed":
        errors.append("external readiness: Figma not-observed 경계가 정확하지 않습니다.")
    expected = {"metadata", "example", "implementation"}
    if set(readiness) != expected or any(readiness.get(stage) != {"status": "not-ready", "evidence": None} for stage in expected):
        errors.append("external readiness: 구현 전 readiness는 모두 not-ready여야 합니다.")


def _validate_schema_drift(root: Path, contract: dict[str, Any], errors: list[str]) -> None:
    storage = contract.get("storage")
    if not isinstance(storage, dict):
        return
    if storage.get("implementationIssues") != [44, 45] or storage.get("migrationSourceOfTruth") != "supabase/migrations" or storage.get("flywayAllowed") is not False or storage.get("schemaChangesInIssue85") is not False:
        errors.append("storage semantic: migration/implementation owner가 정확하지 않습니다.")
    drift = storage.get("schemaDrift")
    if not isinstance(drift, list) or [item.get("id") for item in drift if isinstance(item, dict)] != ["timezone", "revision", "owner-write-rls"]:
        errors.append("storage semantic: schema drift 집합이 정확하지 않습니다.")
    try:
        sql = (root / INITIAL_MIGRATION_RELATIVE).read_text(encoding="utf-8")
    except OSError as exc:
        errors.append(f"storage semantic: initial migration을 읽을 수 없습니다: {exc}")
        return
    start = sql.find("create table trip_plans (")
    end = sql.find("\n);", start)
    table = sql[start:end] if start >= 0 and end >= 0 else ""
    if not table or re.search(r"\btimezone\b", table) or re.search(r"\brevision\b", table):
        errors.append("storage semantic: 문서화한 trip_plans timezone/revision drift와 현재 schema가 다릅니다.")
    if re.search(r"create policy trip_plans_owner_(insert|update|delete)", sql, re.IGNORECASE):
        errors.append("storage semantic: 문서화한 owner write RLS drift와 현재 schema가 다릅니다.")


def _validate_value(value: Any, schema: Any, schemas: dict[str, Any], label: str, errors: list[str]) -> None:
    if not isinstance(schema, dict):
        errors.append(f"{label} schema가 없습니다.")
        return
    reference = schema.get("$ref")
    if isinstance(reference, str):
        target = schemas.get(reference)
        if not isinstance(target, dict):
            errors.append(f"{label} ref {reference} schema가 없습니다.")
            return
        merged = dict(target)
        merged.update({key: item for key, item in schema.items() if key != "$ref"})
        _validate_value(value, merged, schemas, label, errors)
        return
    nullable = schema.get("nullable") is True
    if value is None:
        if not nullable:
            errors.append(f"{label}은 null일 수 없습니다.")
        return
    kind = schema.get("type")
    if kind == "object":
        if not isinstance(value, dict):
            errors.append(f"{label}은 object여야 합니다.")
            return
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False and isinstance(properties, dict):
            unknown = set(value) - set(properties)
            if unknown:
                errors.append(f"{label}에 허용되지 않은 필드가 있습니다: {', '.join(sorted(unknown))}")
        required = schema.get("required", [])
        if isinstance(required, list):
            missing = set(required) - set(value)
            if missing:
                errors.append(f"{label} 필수 필드가 없습니다: {', '.join(sorted(missing))}")
        minimum = schema.get("minProperties")
        if isinstance(minimum, int) and len(value) < minimum:
            errors.append(f"{label}은 최소 {minimum}개 필드가 필요합니다.")
        if isinstance(properties, dict):
            for field, item in value.items():
                child = properties.get(field)
                if isinstance(child, dict):
                    _validate_value(item, child, schemas, f"{label}.{field}", errors)
        return
    if kind == "array":
        if not isinstance(value, list):
            errors.append(f"{label}은 array여야 합니다.")
            return
        for bound, comparison in (("minItems", len(value) < schema.get("minItems", 0)), ("maxItems", len(value) > schema.get("maxItems", math.inf))):
            if comparison:
                errors.append(f"{label}의 {bound} 제약을 위반했습니다.")
        item_schema = schema.get("items")
        for index, item in enumerate(value):
            _validate_value(item, item_schema, schemas, f"{label}[{index}]", errors)
        return
    if kind == "string":
        if not isinstance(value, str):
            errors.append(f"{label}은 string이어야 합니다.")
            return
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append(f"{label} 길이가 너무 짧습니다.")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            errors.append(f"{label} 길이가 너무 깁니다.")
        if "pattern" in schema and re.fullmatch(schema["pattern"], value) is None:
            errors.append(f"{label} pattern이 올바르지 않습니다.")
        if "enum" in schema and value not in schema["enum"]:
            errors.append(f"{label} enum 값이 올바르지 않습니다.")
        normalization = schema.get("normalization")
        normalized = unicodedata.normalize("NFC", value.strip())
        if normalization == "trim+nfc" and value != normalized:
            errors.append(f"{label}은 trim+nfc canonical 문자열이어야 합니다.")
        _validate_format(value, schema.get("format"), label, errors)
        return
    if kind == "integer":
        if not isinstance(value, int) or isinstance(value, bool):
            errors.append(f"{label}은 integer여야 합니다.")
            return
        if "minimum" in schema and value < schema["minimum"]:
            errors.append(f"{label}이 minimum보다 작습니다.")
        if "maximum" in schema and value > schema["maximum"]:
            errors.append(f"{label}이 maximum보다 큽니다.")
        if "enum" in schema and value not in schema["enum"]:
            errors.append(f"{label} enum 값이 올바르지 않습니다.")
        return
    if kind == "boolean":
        if not isinstance(value, bool):
            errors.append(f"{label}은 boolean이어야 합니다.")
        return
    errors.append(f"{label}의 지원하지 않는 schema type입니다.")


def _validate_format(value: str, format_name: Any, label: str, errors: list[str]) -> None:
    if format_name is None:
        return
    try:
        if format_name == "uuid":
            if str(uuid.UUID(value)) != value or value != value.lower():
                raise ValueError
        elif format_name == "date":
            if date.fromisoformat(value).isoformat() != value:
                raise ValueError
        elif format_name == "date-time":
            _parse_datetime(value)
        elif format_name == "iana-timezone":
            if value != "Asia/Seoul":
                raise ValueError
        elif format_name == "uri":
            parsed = urlsplit(value)
            if parsed.scheme not in {"https", "urn"} or (parsed.scheme == "https" and (not parsed.netloc or parsed.username or parsed.password)):
                raise ValueError
        elif format_name == "urn":
            if not value.startswith("urn:timing-jeju:problem:"):
                raise ValueError
        elif format_name == "trace-id":
            if TRACE_ID.fullmatch(value) is None:
                raise ValueError
        else:
            errors.append(f"{label}에 지원하지 않는 format {format_name}이 있습니다.")
    except (TypeError, ValueError):
        errors.append(f"{label}의 {format_name} 형식이 올바르지 않습니다.")


def _validate_datetime(value: Any, label: str, errors: list[str]) -> None:
    try:
        _parse_datetime(value)
    except ValueError:
        errors.append(f"{label}의 date-time 형식이 올바르지 않습니다.")


def _parse_datetime(value: Any) -> datetime:
    if not isinstance(value, str) or RFC3339.fullmatch(value) is None:
        raise ValueError("RFC3339 lexical profile mismatch")
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("offset required")
    return parsed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=REPOSITORY_ROOT)
    args = parser.parse_args()
    errors = validate(args.root.resolve())
    if errors:
        print("여행 CRUD 계약 검사 실패")
        for error in errors:
            print(f"- {error}")
        return 1
    print("여행 CRUD 계약 검사 성공")
    return 0


if __name__ == "__main__":
    sys.exit(main())
