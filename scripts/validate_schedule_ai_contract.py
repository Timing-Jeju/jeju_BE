#!/usr/bin/env python3
"""Issue #89 schedule-ai 계약을 exact fail-closed로 검사한다."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/schedule-ai/contract.json"
CATALOG = ROOT / "docs/contracts/rest/catalog.json"
CANONICAL_DIGEST = "8ede7b980f4d39133b1a81e80af245d2d1f5c4f058105ea7d482178436bf4ece"
IDENTITIES = [
    ("POST", "/api/v1/trips/{tripId}/generation-runs", "compute", [202], [400, 401, 404, 409, 422, 429, 503]),
    ("GET", "/api/v1/trips/{tripId}/generation-runs/{runId}", "read", [200], [400, 401, 404, 410, 429, 503]),
    ("POST", "/api/v1/trips/{tripId}/generation-runs/{runId}/candidates/{candidateId}/apply", "apply", [200], [400, 401, 404, 409, 410, 422, 429, 503]),
    ("POST", "/api/v1/trips/{tripId}/schedule-revision-runs", "compute", [202], [400, 401, 404, 409, 422, 429, 503]),
    ("GET", "/api/v1/trips/{tripId}/schedule-revision-runs/{runId}", "read", [200], [400, 401, 404, 410, 429, 503]),
    ("POST", "/api/v1/trips/{tripId}/schedule-revision-runs/{runId}/candidates/{candidateId}/apply", "apply", [200], [400, 401, 404, 409, 410, 422, 429, 503]),
]
TOP_FIELDS = {
    "schemaVersion", "contractVersion", "sourceSpecVersion", "inherits", "ownerIssue",
    "prerequisiteIssues", "endpointGroups", "implementationOwners", "ownerBindings", "endpoints", "schemas", "commonAlignment",
    "stateResponses", "runningStateVariants", "terminalStateVariants", "intakeIsolationPolicy", "lifecyclePolicy", "headerPolicy", "problemMatrix", "problemDetailsPolicy", "problemConditions",
    "securityPolicy", "locationInputPolicy", "generationSnapshotPolicy", "databasePolicy", "retentionPolicy", "idempotencyPolicy",
    "hashPolicy", "examples", "catalogProjection", "externalTraceability", "readiness", "schemaGaps",
    "excludedScope",
}
APPLY_FIRST_MATCH_PRECEDENCE = [
    "AUTHENTICATION_REQUIRED",
    "INVALID_ACCESS_TOKEN",
    "INVALID_PATH_PARAMETER",
    "INVALID_ASYNC_RUN_REQUEST",
    "IDEMPOTENCY_KEY_REQUIRED",
    "IDEMPOTENCY_KEY_INVALID",
    "IF_MATCH_REQUIRED",
    "IF_MATCH_INVALID",
    "TRIP_NOT_FOUND",
    "ASYNC_RUN_NOT_FOUND",
    "CANDIDATE_NOT_FOUND",
    "IDEMPOTENT_REPLAY",
    "IDEMPOTENCY_KEY_REUSED",
    "CANDIDATE_ALREADY_APPLIED",
    "CANDIDATE_NOT_APPLICABLE_RUN_STATUS",
    "CANDIDATE_EXPIRED",
    "ACTIVE_SCHEDULE_VERSION_CONFLICT",
    "CANDIDATE_STALE",
    "CANDIDATE_NOT_APPLICABLE_LINEAGE",
    "ASYNC_RUN_QUOTA_EXCEEDED",
    "ASYNC_RESULT_TEMPORARILY_UNAVAILABLE",
    "APPLY_SUCCESS",
]


class DuplicateKey(ValueError):
    pass


def _closed_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKey(f"중복 JSON key: {key}")
        result[key] = value
    return result


def _load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_closed_object)


def _digest(value: Any) -> str:
    canonical = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def validate_terminal_payload(payload: dict[str, Any], phase: str) -> list[str]:
    """Validate the closed terminal provenance phase at the MCP call-log boundary."""
    errors: list[str] = []
    phase_fields = {
        "preStart": (False, False),
        "startedPreDispatch": (True, False),
        "postDispatch": (True, True),
    }
    if phase not in phase_fields:
        return [f"알 수 없는 terminal provenance phase입니다: {phase}"]
    if payload.get("status") not in {"failed", "cancelled"}:
        errors.append("terminal provenance payload status는 failed/cancelled여야 합니다.")
    for field in ["completedAt", "failure"]:
        if field not in payload or payload[field] is None:
            errors.append(f"terminal payload에 non-null {field}가 필요합니다.")

    started_required, hash_required = phase_fields[phase]
    for field, required in [("startedAt", started_required), ("mcpInputHash", hash_required)]:
        present = field in payload
        if present != required:
            policy = "필수" if required else "omitted"
            errors.append(f"{phase}에서 {field}는 {policy}여야 합니다.")
        elif present and payload[field] is None:
            errors.append(f"{phase}에서 {field}는 null일 수 없습니다.")
    if hash_required and not re.fullmatch(r"[0-9a-f]{64}", str(payload.get("mcpInputHash", ""))):
        errors.append("postDispatch mcpInputHash는 lowercase 64-hex여야 합니다.")
    return errors


def validate_running_payload(payload: dict[str, Any], phase: str) -> list[str]:
    """Validate an observable running projection before or after MCP dispatch."""
    errors: list[str] = []
    hash_required_by_phase = {
        "startedPreDispatch": False,
        "postDispatch": True,
    }
    if phase not in hash_required_by_phase:
        return [f"알 수 없는 running provenance phase입니다: {phase}"]
    if payload.get("status") != "running":
        errors.append("running provenance payload status는 running이어야 합니다.")
    if "startedAt" not in payload or payload.get("startedAt") is None:
        errors.append("running payload에 non-null startedAt이 필요합니다.")
    for field in ["completedAt", "result", "failure"]:
        if field in payload:
            errors.append(f"running payload에서 {field}는 omitted여야 합니다.")

    hash_required = hash_required_by_phase[phase]
    hash_present = "mcpInputHash" in payload
    if hash_present != hash_required:
        policy = "필수" if hash_required else "omitted"
        errors.append(f"{phase}에서 mcpInputHash는 {policy}여야 합니다.")
    elif hash_present and payload["mcpInputHash"] is None:
        errors.append(f"{phase}에서 mcpInputHash는 null일 수 없습니다.")
    if hash_required and not re.fullmatch(r"[0-9a-f]{64}", str(payload.get("mcpInputHash", ""))):
        errors.append("postDispatch mcpInputHash는 lowercase 64-hex여야 합니다.")
    return errors


def resolve_apply_overlap(state: dict[str, Any]) -> str:
    """Return the one observable result selected by the canonical apply ordering."""
    rules = [
        (not state.get("authenticated", True), "AUTHENTICATION_REQUIRED"),
        (not state.get("accessTokenValid", True), "INVALID_ACCESS_TOKEN"),
        (not state.get("pathValid", True), "INVALID_PATH_PARAMETER"),
        (not state.get("requestValid", True), "INVALID_ASYNC_RUN_REQUEST"),
        (not state.get("idempotencyKeyPresent", True), "IDEMPOTENCY_KEY_REQUIRED"),
        (not state.get("idempotencyKeyValid", True), "IDEMPOTENCY_KEY_INVALID"),
        (not state.get("ifMatchPresent", True), "IF_MATCH_REQUIRED"),
        (not state.get("ifMatchValid", True), "IF_MATCH_INVALID"),
        (not state.get("tripVisible", True), "TRIP_NOT_FOUND"),
        (not state.get("runVisibleAndLinked", True), "ASYNC_RUN_NOT_FOUND"),
        (not state.get("candidateVisibleAndLinked", True), "CANDIDATE_NOT_FOUND"),
        (state.get("replayCompleted", False), "IDEMPOTENT_REPLAY"),
        (state.get("idempotencyConflict", False), "IDEMPOTENCY_KEY_REUSED"),
        (state.get("alreadyApplied", False), "CANDIDATE_ALREADY_APPLIED"),
        (
            not state.get("runSucceeded", True)
            or not state.get("candidateSelectable", True),
            "CANDIDATE_NOT_APPLICABLE",
        ),
        (state.get("expired", False), "CANDIDATE_EXPIRED"),
        (
            state.get("requestedVersion") != state.get("lockedActiveVersion"),
            "ACTIVE_SCHEDULE_VERSION_CONFLICT",
        ),
        (
            state.get("candidateBaseVersion") != state.get("lockedActiveVersion"),
            "CANDIDATE_STALE",
        ),
        (
            not state.get("candidateLineageApplicable", True),
            "CANDIDATE_NOT_APPLICABLE",
        ),
        (not state.get("quotaAvailable", True), "ASYNC_RUN_QUOTA_EXCEEDED"),
        (
            not state.get("storageAvailable", True),
            "ASYNC_RESULT_TEMPORARILY_UNAVAILABLE",
        ),
    ]
    return next((outcome for matches, outcome in rules if matches), "APPLY_SUCCESS")


def validate(contract_path: Path, catalog_path: Path = CATALOG) -> list[str]:
    errors: list[str] = []
    try:
        contract = _load(contract_path)
        catalog = _load(catalog_path)
    except (OSError, UnicodeError, json.JSONDecodeError, DuplicateKey) as error:
        return [f"계약 JSON을 읽을 수 없습니다: {error}"]

    if not isinstance(contract, dict) or set(contract) != TOP_FIELDS:
        errors.append("schedule-ai contract root는 exact closed object여야 합니다.")
        return errors
    if _digest(contract) != CANONICAL_DIGEST:
        errors.append("Issue #89 canonical 계약 semantic digest가 변경되었습니다.")

    identities = [(item.get("method"), item.get("path")) for item in contract.get("endpoints", []) if isinstance(item, dict)]
    expected_pairs = [(method, path) for method, path, *_ in IDENTITIES]
    if identities != expected_pairs or len(set(identities)) != 6:
        errors.append("Issue #89 여섯 endpoint identity/order가 정확하지 않습니다.")

    apply_endpoints = [
        endpoint
        for endpoint in contract.get("endpoints", [])
        if isinstance(endpoint, dict) and endpoint.get("operation") == "apply"
    ]
    if len(apply_endpoints) != 2 or any(
        endpoint.get("firstMatchPrecedence") != APPLY_FIRST_MATCH_PRECEDENCE
        for endpoint in apply_endpoints
    ):
        errors.append("두 apply endpoint의 ordered first-match precedence가 정확하지 않습니다.")

    common_auth = catalog.get("commonRules", {}).get("authorization", {}) if isinstance(catalog, dict) else {}
    alignment = contract.get("commonAlignment", {}).get("authentication")
    expected_auth = {
        "missingTokenCode": common_auth.get("missingTokenCode"),
        "invalidTokenCode": common_auth.get("invalidTokenCode"),
    }
    if alignment != expected_auth or alignment != {
        "missingTokenCode": "AUTHENTICATION_REQUIRED",
        "invalidTokenCode": "INVALID_ACCESS_TOKEN",
    }:
        errors.append("required 인증 code가 commonRules와 양방향 exact 정렬되지 않았습니다.")
    if contract.get("problemMatrix", {}).get("401") != list(expected_auth.values()):
        errors.append("401 Problem matrix가 commonRules missing/invalid token code와 다릅니다.")

    catalog_endpoints = catalog.get("endpoints") if isinstance(catalog, dict) else None
    if not isinstance(catalog_endpoints, list):
        errors.append("REST catalog endpoints를 읽을 수 없습니다.")
    else:
        selected = [item for item in catalog_endpoints if isinstance(item, dict) and (item.get("method"), item.get("path")) in expected_pairs]
        if len(selected) != 6:
            errors.append("REST catalog에 Issue #89 endpoint가 exactly once 존재해야 합니다.")
        if selected != contract.get("catalogProjection"):
            errors.append("REST catalog Issue #89 여섯 행의 모든 canonical projection field가 다릅니다.")
        by_identity = {(item.get("method"), item.get("path")): item for item in selected}
        for method, path, operation, success, failure in IDENTITIES:
            item = by_identity.get((method, path), {})
            if item.get("operation") != operation or item.get("responses") != {"success": success, "errors": failure}:
                errors.append(f"REST catalog operation/status가 canonical 계약과 다릅니다: {method} {path}")
            if item.get("contractVersion") != "1.0.0" or "#89" not in str(item.get("owner", "")):
                errors.append(f"REST catalog owner/version traceability가 다릅니다: {method} {path}")

    expected_schemas = {
        "TripPath", "AsyncRunPath", "AsyncCandidatePath", "NoQuery", "BodyForbidden",
        "AsyncCreateHeaders", "AsyncReadHeaders", "AsyncApplyHeaders",
        "GenerationRunRequest", "ScheduleRevisionRunRequest", "ApplyCandidateRequest",
        "AsyncRunAccepted", "AsyncFailure", "GenerationCandidate", "RevisionCandidate",
        "GenerationResult", "RevisionDiff", "RevisionResult", "GenerationRunStatus", "RevisionRunStatus",
        "ApplyCandidateResponse",
    }
    schemas = contract.get("schemas")
    if not isinstance(schemas, dict) or set(schemas) != expected_schemas:
        errors.append("OpenAPI schema exact set가 다릅니다.")
    else:
        for name, schema in schemas.items():
            if name == "BodyForbidden":
                if schema != {"kind": "forbidden", "accepts": []}:
                    errors.append("GET body sentinel은 empty object/null도 거부하는 forbidden/absent여야 합니다.")
                continue
            if not isinstance(schema, dict) or schema.get("additionalProperties") is not False or not isinstance(schema.get("required"), list) or not isinstance(schema.get("properties"), dict):
                errors.append(f"{name} schema는 closed typed object여야 합니다.")
        expected_idempotency_key = {
            "type": "string",
            "minLength": 1,
            "maxLength": 128,
            "pattern": "^[ -~]+$",
            "nullable": False,
        }
        for name in ["AsyncCreateHeaders", "AsyncApplyHeaders"]:
            key_schema = schemas.get(name, {}).get("properties", {}).get("Idempotency-Key")
            if key_schema != expected_idempotency_key:
                errors.append(f"{name} Idempotency-Key는 1..128 printable ASCII여야 합니다.")
    for endpoint in contract.get("endpoints", []):
        references = endpoint.get("schemas") if isinstance(endpoint, dict) else None
        if not isinstance(references, dict) or set(references) != {"path", "query", "headers", "body"} or not set(references.values()) <= expected_schemas:
            errors.append(f"endpoint path/query/header/body schema가 닫혀 있지 않습니다: {endpoint.get('path') if isinstance(endpoint, dict) else 'unknown'}")

    expected_location_policy = {
        "receiveCurrentOrIndirectLocation": False,
        "unknownFields": "reject",
        "allowedUserSelectedReferences": ["regionCode", "placeId", "tripItemId"],
        "tripItemIdOwnership": "owned trip item only",
        "forbiddenFields": [
            "accuracy", "altitude", "currentLocation", "currentPlaceId", "deviceLocation",
            "geohash", "gps", "gridX", "gridY", "heading", "latitude",
            "locationSupplied", "longitude", "speed",
        ],
    }
    if contract.get("locationInputPolicy") != expected_location_policy:
        errors.append("#223/#224 위치 무수신과 사용자 직접 선택 reference allowlist가 정확하지 않습니다.")

    expected_generation_snapshot = {
        "dayActivityWindow": {
            "source": "trip_days.activity_start_time + trip_days.activity_end_time",
            "snapshot": "immutable exact stored pair for targetDayId",
            "missing": "explicitly absent",
            "defaulting": "forbidden; absence is never persisted or reported as a user fact",
        },
        "transportEvents": {
            "source": "trip_transport_events",
            "slots": ["arrival", "departure"],
            "eventTypes": ["arrival", "departure"],
            "transportTypes": ["flight", "ferry"],
            "fields": [
                "eventType", "transportType", "terminalPlaceId", "customTerminalName",
                "scheduledAt", "transportNumber", "note",
            ],
            "terminalSelector": "exactly one of terminalPlaceId or customTerminalName",
            "scheduledAt": "RFC3339 date-time with mandatory +09:00 offset",
            "missingSlot": "null",
        },
    }
    if contract.get("generationSnapshotPolicy") != expected_generation_snapshot:
        errors.append("#239 day activity window와 #47/#180 transport event snapshot 계약이 정확하지 않습니다.")

    matrix_codes = {code for codes in contract.get("problemMatrix", {}).values() if isinstance(codes, list) for code in codes}
    conditions = contract.get("problemConditions")
    condition_by_code = {item.get("code"): item for item in conditions if isinstance(item, dict)} if isinstance(conditions, list) else {}
    if set(condition_by_code) != matrix_codes or len(condition_by_code) != len(conditions or []):
        errors.append("Problem matrix와 condition code가 exact bijection이 아닙니다.")
    problem_fields = {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"}
    condition_fields = {"code", "status", "condition", "type", "title", "detail", "fieldErrors", "endpoints", "example"}
    for code, condition in condition_by_code.items():
        example = condition.get("example")
        if set(condition) != condition_fields or not isinstance(condition.get("endpoints"), list) or not condition["endpoints"]:
            errors.append(f"Problem condition shape/endpoints가 정확하지 않습니다: {code}")
            continue
        if not isinstance(example, dict) or set(example) != problem_fields:
            errors.append(f"Problem example 8필드가 정확하지 않습니다: {code}")
            continue
        status_key = str(condition.get("status"))
        if code not in contract["problemMatrix"].get(status_key, []) or any(example.get(field) != condition.get(field) for field in ["code", "status", "type", "title", "detail", "fieldErrors"]):
            errors.append(f"Problem condition/example/matrix가 정렬되지 않았습니다: {code}")

    groups = contract.get("endpointGroups")
    endpoint_ids = {f"{method} {path}" for method, path in expected_pairs}
    if not isinstance(groups, dict) or any(not isinstance(members, list) or not members or not set(members) <= endpoint_ids for members in groups.values()):
        errors.append("Problem endpoint group이 six endpoint 안에서 닫혀 있지 않습니다.")
    else:
        scoped_codes = {endpoint_id: set() for endpoint_id in endpoint_ids}
        for code, condition in condition_by_code.items():
            for group in condition.get("endpoints", []):
                if group not in groups:
                    errors.append(f"Problem condition endpoint group을 찾을 수 없습니다: {code}/{group}")
                    continue
                for endpoint_id in groups[group]:
                    scoped_codes[endpoint_id].add(code)
        for endpoint in contract.get("endpoints", []):
            endpoint_id = f"{endpoint.get('method')} {endpoint.get('path')}"
            error_matrix = endpoint.get("errorMatrix")
            if not isinstance(error_matrix, dict) or set(error_matrix) != {str(status) for status in endpoint.get("errors", [])}:
                errors.append(f"endpoint별 Problem status matrix가 정확하지 않습니다: {endpoint_id}")
                continue
            if {code for codes in error_matrix.values() for code in codes} != scoped_codes.get(endpoint_id):
                errors.append(f"endpoint별 Problem code scope가 condition과 다릅니다: {endpoint_id}")

    owners = contract.get("implementationOwners", {})
    if owners != {"generationIntake":53,"generationResultRead":95,"generationWorkerAndCandidate":79,"generationApply":54,"revisionIntake":69,"revisionResultRead":105,"revisionWorkerAndCandidate":104,"revisionApply":81,"commandSnapshotMigration":108,"workerLifecycle":74}:
        errors.append("8개 endpoint 구현 owner와 공통 owner mapping이 정확하지 않습니다.")
    bindings = contract.get("ownerBindings", {})
    expected_endpoint_bindings = {
        expected_pairs[0]: "generationIntake", expected_pairs[1]: "generationResultRead", expected_pairs[2]: "generationApply",
        expected_pairs[3]: "revisionIntake", expected_pairs[4]: "revisionResultRead", expected_pairs[5]: "revisionApply",
    }
    actual_endpoint_bindings = {}
    if isinstance(bindings, dict) and isinstance(bindings.get("endpoints"), dict):
        for identity, owner_key in bindings["endpoints"].items():
            method, separator, path = identity.partition(" ")
            if separator:
                actual_endpoint_bindings[(method, path)] = owner_key
    if actual_endpoint_bindings != expected_endpoint_bindings or any(owner_key not in owners for owner_key in actual_endpoint_bindings.values()):
        errors.append("6 endpoint owner binding이 implementationOwners와 양방향 정렬되지 않았습니다.")
    if bindings.get("readback") != {"generationResult":"generationResultRead","revisionResult":"revisionResultRead"} or bindings.get("workerCandidates") != {"generation":"generationWorkerAndCandidate","revision":"revisionWorkerAndCandidate"}:
        errors.append("#95/#105 readback과 #79/#104 candidate owner binding이 정확하지 않습니다.")

    expected_running = {
        "discriminator": "DB provenance + startedAt/mcpInputHash presence",
        "oneOf": {
            "startedPreDispatch": {
                "required": ["startedAt"],
                "nullable": [],
                "omitted": ["mcpInputHash"],
                "provenance": "worker started but no matching #52 MCP call log exists before dispatch",
            },
            "postDispatch": {
                "required": ["startedAt", "mcpInputHash"],
                "nullable": [],
                "omitted": [],
                "provenance": "matching #52 MCP call log owns the validated exact wire mcpInputHash",
            },
        },
    }
    if contract.get("runningStateVariants") != expected_running:
        errors.append("running startedPreDispatch/postDispatch oneOf가 정확하지 않습니다.")
    running_response = contract.get("stateResponses", {}).get("running", {})
    if running_response != {
        "required": ["contractVersion", "runId", "status", "pollUrl", "commandInputHash", "createdAt", "startedAt"],
        "nullable": [],
        "omitted": ["completedAt", "result", "failure"],
        "retryAfter": "2",
        "oneOf": ["startedPreDispatch", "postDispatch"],
    }:
        errors.append("running response projection이 conditional provenance oneOf와 다릅니다.")

    terminal = contract.get("terminalStateVariants")
    expected_terminal = {
        "discriminator": "DB provenance + startedAt/mcpInputHash presence",
        "oneOf": {
            "preStart": {
                "required": ["completedAt", "failure"],
                "nullable": [],
                "omitted": ["startedAt", "mcpInputHash"],
                "provenance": "terminal before worker claim/start; no matching MCP call log",
            },
            "startedPreDispatch": {
                "required": ["startedAt"],
                "nullable": [],
                "omitted": ["mcpInputHash"],
                "provenance": "worker started but no matching #52 MCP call log exists before dispatch",
            },
            "postDispatch": {
                "required": ["startedAt", "mcpInputHash"],
                "nullable": [],
                "omitted": [],
                "provenance": "matching #52 MCP call log owns the validated exact wire mcpInputHash",
            },
        },
    }
    if terminal != expected_terminal:
        errors.append("failed/cancelled terminal oneOf discriminator가 정확하지 않습니다.")
    else:
        examples = contract.get("examples", {})
        for example_name, phase in [
            ("failedPreStart", "preStart"),
            ("cancelledStartedPreDispatch", "startedPreDispatch"),
            ("failedPostDispatch", "postDispatch"),
        ]:
            for error in validate_terminal_payload(examples.get(example_name, {}), phase):
                errors.append(f"terminal example {example_name}: {error}")
    for state_name in ["failed", "cancelled"]:
        if contract.get("stateResponses", {}).get(state_name, {}).get("oneOf") != ["preStart", "startedPreDispatch", "postDispatch"]:
            errors.append(f"{state_name} response가 terminal oneOf를 참조하지 않습니다.")
    expected_terminal_provenance = {
        "preStart": "run.started_at IS NULL and no matching MCP call log",
        "startedPreDispatch": "run.started_at IS NOT NULL and no matching MCP call log",
        "postDispatch": "run.started_at IS NOT NULL and matching #52 MCP call log owns validated mcpInputHash",
    }
    if contract.get("databasePolicy", {}).get("terminalProvenance") != expected_terminal_provenance:
        errors.append("DB terminal provenance와 3-way discriminator가 exact 정렬되지 않았습니다.")

    intake = contract.get("intakeIsolationPolicy")
    intake_condition = condition_by_code.get("ASYNC_INTAKE_UNAVAILABLE", {})
    if not isinstance(intake, dict) or intake.get("owners") != [53, 69] or intake.get("workerUnavailable") != "202 queued is durable even when worker or private MCP is unavailable":
        errors.append("#53/#69 durable intake와 worker/MCP 격리 정책이 정확하지 않습니다.")
    if "persistence" not in str(intake_condition.get("condition")) or "queue admission" not in str(intake_condition.get("condition")) or "MCP" in str(intake_condition.get("condition")):
        errors.append("create 503은 intake persistence/queue admission 장애로만 제한해야 합니다.")

    domains = catalog.get("domainContracts", []) if isinstance(catalog, dict) else []
    issue89 = [item for item in domains if isinstance(item, dict) and item.get("issue") == 89]
    expected_domain = {
        "issue": 89,
        "domain": "schedule-ai",
        "inherits": "timing-jeju-rest-contract/v1",
        "versions": {"local": "1.0.0", "notion": "not-linked", "figma": "not-linked"},
        "readiness": {
            "metadata": {"status": "not-ready", "evidence": None},
            "example": {"status": "not-ready", "evidence": None},
            "implementation": {"status": "not-ready", "evidence": None},
        },
    }
    if issue89 != [expected_domain]:
        errors.append("REST catalog Issue #89 local/external readiness가 fail-closed 상태와 다릅니다.")

    evidence = contract["readiness"]["local"]["evidence"]
    expected_files = {
        "document": "docs/contracts/domains/schedule-ai/contract.md",
        "machineContract": "docs/contracts/domains/schedule-ai/contract.json",
        "validator": "scripts/validate_schedule_ai_contract.py",
        "contractTest": "scripts/tests/test_schedule_ai_contract.py",
    }
    for field, relative in expected_files.items():
        if evidence.get(field) != relative or not (ROOT / relative).is_file():
            errors.append(f"local readiness evidence가 없거나 경로가 다릅니다: {field}")
    if evidence.get("issue") != "https://github.com/Timing-Jeju/jeju_BE/issues/89":
        errors.append("Issue #89 traceability URL이 정확하지 않습니다.")

    migrations = "\n".join(
        path.read_text(encoding="utf-8")
        for path in [
            ROOT / "supabase/migrations/20260728000000_initial_public_schema.sql",
            ROOT / "supabase/migrations/20260830000000_schedule_revision_run_foundation.sql",
            ROOT / "supabase/migrations/20260831000000_compute_run_input_snapshot.sql",
        ]
    )
    for required in [
        "create table itinerary_generation_runs",
        "create table itinerary_generation_candidates",
        "create table public.schedule_revision_runs",
        "generation_run_id uuid",
        "schedule_revision_run_id uuid",
        "'itinerary_generation', 'schedule_revision'",
        "constraint chk_compute_run_inputs_exact_parent",
    ]:
        if required not in migrations:
            errors.append(f"authoritative migration evidence가 없습니다: {required}")
    if "create table public.schedule_revision_candidates" in migrations:
        errors.append("revision candidate schema gap이 해소됐으므로 #104 owner 계약을 재검토해야 합니다.")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    args = parser.parse_args()
    errors = validate(args.contract, args.catalog)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("Issue #89 schedule-ai 계약 검증 통과")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
