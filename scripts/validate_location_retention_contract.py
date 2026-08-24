#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/location-retention/contract.json"
DEFAULT_FIXTURE = ROOT / "fixtures/contracts/location-retention/policy.json"

TOP_LEVEL_KEYS = {
    "schemaVersion", "contractVersion", "ownerIssue", "excludedScope", "releaseGate",
    "consentPolicy", "processingPolicies", "deletionWorkflows", "deletionFieldActions", "auditPolicy", "securityPolicy", "accessPolicy",
}
POLICY_KEYS = {"id", "target", "purpose", "storage", "precision", "retention", "deletionTriggers", "disposition"}
RELEASE_GATE = {
    "developmentAllowed": True,
    "internalQaAllowed": True,
    "productionDefaultEnabled": False,
    "releaseIssue": 168,
    "privacyLegalCompleted": False,
    "governmentFilingCompleted": False,
    "activationRule": "all Issue #168 evidence must be complete before production enable",
}
CONSENT_POLICY = {
    "documentType": "location",
    "initialVersion": "2026-08-11.v1",
    "requiredFor": ["live_state", "execution_event", "idle_time_recommendation", "recovery_option", "live_recalculation", "departure_notification"],
    "availableWithoutConsent": ["place_search", "place_detail", "trip_manual_crud", "schedule_manual_crud"],
    "missingConsentError": "LOCATION_CONSENT_REQUIRED",
    "withdrawal": "block new location processing immediately and schedule redaction",
}
EXCLUDED_SCOPE = ["government filing", "app-store submission", "TTL job", "public location API", "continuous tracking"]
DELETION_WORKFLOWS = {
    "consentWithdrawal": ["record_withdrawal", "disable_location_features", "reject_new_location", "redact_live", "redact_events", "redact_commands", "preserve_non_location_audit"],
    "tripEnd": ["end_trip", "redact_live_within_24h", "redact_event_location_by_7d", "preserve_non_location_progress"],
    "tripDeletion": ["block_location", "redact_or_delete_owned_location", "preserve_external_shared_facts", "preserve_redacted_run_lineage"],
    "accountDeletion": ["block_new_requests", "redact_location_first", "delete_owned_aggregates", "delete_auth_user", "preserve_minimal_non_location_job_status"],
}
DELETION_FIELD_ACTIONS = {
    "consentWithdrawal": {
        "redact": ["trip_execution_events.location", "live_state_snapshots.current_location", "async_command_snapshot.location_fields", "mcp_compute_call_logs.location_fields"],
        "delete": [],
        "preserve": ["userId", "runId", "tripId", "scheduleVersionId", "eventType", "status", "policyVersion", "retentionRule", "redactedAt", "errorCode", "traceId"],
    },
    "tripEnd": {
        "redact": ["live_state_snapshots.current_location", "trip_execution_events.location"],
        "delete": [],
        "preserve": ["tripId", "scheduleVersionId", "eventType", "occurredAt", "runId", "status", "policyVersion", "redactedAt"],
    },
    "tripDeletion": {
        "redact": ["async_command_snapshot.location_fields", "mcp_compute_call_logs.location_fields"],
        "delete": ["trip_execution_events.*", "live_state_snapshots.*"],
        "preserve": ["runId", "status", "policyVersion", "redactedAt", "externalSnapshotHash", "errorCode", "traceId"],
    },
    "accountDeletion": {
        "redact": ["async_command_snapshot.location_fields", "mcp_compute_call_logs.location_fields"],
        "delete": ["trip_execution_events.*", "live_state_snapshots.*", "user_owned_trip_aggregates.*", "auth.users.id"],
        "preserve": ["deletionJobId", "status", "policyVersion", "redactedAt", "errorCode", "traceId"],
    },
}
EXPECTED_RETENTION = {
    "trip_execution_event_location": {"mode": "earliestOf", "rules": [{"anchor": "tripEndedAt", "duration": "P7D"}, {"anchor": "lastEventAt", "duration": "P14D"}]},
    "live_state_current_location": {"mode": "earliestOf", "rules": [{"anchor": "tripEndedAt", "duration": "PT24H"}, {"anchor": "createdAt", "duration": "PT72H"}]},
    "async_command_location": {"mode": "earliestOf", "rules": [{"anchor": "terminalAt", "duration": "PT24H"}, {"anchor": "tripEndedAt", "duration": "PT24H"}]},
    "fastapi_mcp_location": {"mode": "none", "rules": []},
    "observability_location": {"mode": "none", "rules": []},
}
EXPECTED_POLICY_CORE = {
    "trip_execution_event_location": ("trip_execution_events.location", "allowed", "WGS84 point; accuracy at most 100m"),
    "live_state_current_location": ("live_state_snapshots.current_location", "allowed", "WGS84 point; accuracy at most 100m"),
    "async_command_location": ("asynchronous command snapshot location", "limited", "100m grid or place/stop id preferred"),
    "fastapi_mcp_location": ("FastAPI MCP input", "forbidden", "100m grid or place/stop id or travel facts"),
    "observability_location": ("logs, metrics, traces and Problem Details", "forbidden", "no coordinates, accuracy or original location payload"),
}
PROHIBITED_FIXTURE_KEYS = ["lat", "lng", "latitude", "longitude", "coordinates", "accuracyMeters", "accessToken", "providerToken", "apiKey", "prompt", "completion", "rawPayload"]
CANONICAL_CONTRACT_SHA256 = "d98c67bfcd691db4dc2069cc7df085c5bbd7b23ad6525979a912882fb6128aee"
CANONICAL_FIXTURE_SHA256 = "e65ed0b17f268a631808018771122dfe70ef5cb5b47978fac8c1bbabcb172002"
_DURATION = re.compile(r"^P(?:(\d+)D)?(?:T(?:(\d+)H)?)?$")
_COORDINATE_PAIR = re.compile(r"(?<![\d.])-?(?:[1-8]?\d(?:\.\d+)?|90(?:\.0+)?)\s*[,/]\s*-?(?:1[0-7]\d(?:\.\d+)?|180(?:\.0+)?|\d?\d(?:\.\d+)?)(?![\d.])")


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _parse_instant(value: str) -> datetime:
    _require(isinstance(value, str), "시각은 문자열이어야 합니다.")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError("시각은 timezone을 포함한 ISO-8601이어야 합니다.") from exc
    _require(parsed.tzinfo is not None, "시각은 timezone을 포함해야 합니다.")
    return parsed.astimezone(timezone.utc)


def _parse_duration(value: str) -> timedelta:
    match = _DURATION.fullmatch(value) if isinstance(value, str) else None
    _require(match is not None, "보존기간은 일 또는 시간 단위 ISO-8601이어야 합니다.")
    days = int(match.group(1) or 0)
    hours = int(match.group(2) or 0)
    _require(days > 0 or hours > 0, "보존기간은 0보다 커야 합니다.")
    return timedelta(days=days, hours=hours)


def retention_cutoff(retention: dict[str, Any], anchors: dict[str, str | None], evaluated_at: str | datetime) -> datetime | None:
    _require(isinstance(retention, dict), "retention은 객체여야 합니다.")
    _require(set(retention) == {"mode", "rules"}, "retention 객체는 닫혀 있어야 합니다.")
    _require(retention["mode"] == "earliestOf", "계산 가능한 retention mode는 earliestOf여야 합니다.")
    rules = retention["rules"]
    _require(isinstance(rules, list) and rules, "retention rules가 필요합니다.")
    evaluated = _parse_instant(evaluated_at) if isinstance(evaluated_at, str) else evaluated_at.astimezone(timezone.utc)
    candidates = []
    for rule in rules:
        _require(isinstance(rule, dict) and set(rule) == {"anchor", "duration"}, "retention rule은 닫힌 객체여야 합니다.")
        anchor = rule["anchor"]
        observed = anchors.get(anchor)
        if observed is None:
            continue
        observed_at = _parse_instant(observed)
        if observed_at > evaluated:
            continue
        candidates.append(observed_at + _parse_duration(rule["duration"]))
    return min(candidates) if candidates else None


def is_expired(now: str | datetime, cutoff: datetime | None) -> bool:
    if cutoff is None:
        return False
    instant = _parse_instant(now) if isinstance(now, str) else now.astimezone(timezone.utc)
    return instant >= cutoff.astimezone(timezone.utc)


def assert_no_sensitive_fixture_values(value: Any) -> None:
    prohibited = {key.casefold() for key in PROHIBITED_FIXTURE_KEYS}
    if isinstance(value, dict):
        for key, child in value.items():
            _require(isinstance(key, str), "fixture key는 문자열이어야 합니다.")
            if key.casefold() in prohibited:
                raise ValueError(f"민감 위치 또는 비밀정보 fixture key는 금지됩니다: {key}")
            assert_no_sensitive_fixture_values(child)
    elif isinstance(value, list):
        for child in value:
            assert_no_sensitive_fixture_values(child)
    elif isinstance(value, float):
        _require(value == value and value not in (float("inf"), float("-inf")), "fixture non-finite number는 금지됩니다.")
    elif isinstance(value, str) and _COORDINATE_PAIR.search(value):
        raise ValueError("민감 위치 또는 비밀정보로 해석될 수 있는 좌표쌍 fixture 값은 금지됩니다.")


def canonical_digest(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_contract(contract: Any, fixture: Any) -> None:
    _require(isinstance(contract, dict), "계약 root는 객체여야 합니다.")
    _require(set(contract) == TOP_LEVEL_KEYS, "계약 root는 canonical closed object여야 합니다.")
    _require(contract["schemaVersion"] == "timing-jeju-location-retention-policy/v1", "schemaVersion이 올바르지 않습니다.")
    _require(contract["contractVersion"] == "1.0.0", "contractVersion이 올바르지 않습니다.")
    _require(contract["ownerIssue"] == 73 and not isinstance(contract["ownerIssue"], bool), "ownerIssue는 73이어야 합니다.")
    _require(canonical_digest(contract) == CANONICAL_CONTRACT_SHA256, "계약 canonical tree가 변경됐습니다.")
    _require(contract["excludedScope"] == EXCLUDED_SCOPE, "제외 범위가 canonical 값과 다릅니다.")
    _require(contract["releaseGate"] == RELEASE_GATE, "개발·출시 gate가 canonical 값과 다릅니다.")
    _require(contract["consentPolicy"] == CONSENT_POLICY, "동의 정책이 canonical 값과 다릅니다.")
    policies = contract["processingPolicies"]
    _require(isinstance(policies, list) and len(policies) == 5, "처리 정책은 정확히 5개여야 합니다.")
    by_id: dict[str, dict[str, Any]] = {}
    for policy in policies:
        _require(isinstance(policy, dict) and set(policy) == POLICY_KEYS, "처리 정책은 canonical closed object여야 합니다.")
        _require(isinstance(policy["id"], str) and policy["id"] not in by_id, "처리 정책 id는 고유해야 합니다.")
        _require(isinstance(policy["purpose"], str) and bool(policy["purpose"].strip()), "처리 목적이 필요합니다.")
        _require(isinstance(policy["deletionTriggers"], list) and bool(policy["deletionTriggers"]), "삭제 trigger가 필요합니다.")
        _require(isinstance(policy["disposition"], str) and bool(policy["disposition"].strip()), "redaction disposition이 필요합니다.")
        by_id[policy["id"]] = policy
    _require(set(by_id) == set(EXPECTED_POLICY_CORE), "처리 정책 id가 canonical 집합과 다릅니다.")
    for policy_id, (target, storage, precision) in EXPECTED_POLICY_CORE.items():
        policy = by_id[policy_id]
        _require((policy["target"], policy["storage"], policy["precision"]) == (target, storage, precision), f"{policy_id} 처리 경계가 다릅니다.")
        _require(policy["retention"] == EXPECTED_RETENTION[policy_id], f"{policy_id} 보존 규칙이 다릅니다.")
    _require(contract["deletionWorkflows"] == DELETION_WORKFLOWS, "삭제 순서가 canonical 값과 다릅니다.")
    _require(contract["deletionFieldActions"] == DELETION_FIELD_ACTIONS, "삭제 workflow field action이 canonical 값과 다릅니다.")
    _require(set(contract["deletionWorkflows"]) == set(contract["deletionFieldActions"]), "삭제 workflow와 field action ID가 일치해야 합니다.")
    audit = contract["auditPolicy"]
    _require(isinstance(audit, dict) and set(audit) == {"allowedNonLocationFields", "forbiddenValues"}, "감사 정책은 닫힌 객체여야 합니다.")
    _require(audit["allowedNonLocationFields"] == ["userId", "runId", "tripId", "scheduleVersionId", "eventType", "occurredAt", "deletionJobId", "status", "policyVersion", "retentionRule", "redactedAt", "externalSnapshotHash", "errorCode", "traceId"], "비위치 감사 allowlist가 다릅니다.")
    allowed_audit_fields = set(audit["allowedNonLocationFields"])
    for workflow, actions in contract["deletionFieldActions"].items():
        _require(set(actions) == {"redact", "delete", "preserve"}, f"{workflow} field action은 닫힌 객체여야 합니다.")
        _require(set(actions["preserve"]) <= allowed_audit_fields, f"{workflow} preserve field는 audit allowlist의 부분집합이어야 합니다.")
    security = contract["securityPolicy"]
    _require(isinstance(security, dict) and set(security) == {"prohibitedFixtureKeys", "prohibitedOutputs", "migrationOwnedByIssue", "publicApiOwnedByIssue"}, "보안 정책은 닫힌 객체여야 합니다.")
    _require(security["prohibitedFixtureKeys"] == PROHIBITED_FIXTURE_KEYS, "fixture 금지 key가 다릅니다.")
    _require(security["migrationOwnedByIssue"] is None and security["publicApiOwnedByIssue"] is None, "#73은 migration이나 공개 API를 소유하지 않습니다.")
    _require(contract["accessPolicy"] == {
        "publicDatabaseDirectAccess": "forbidden",
        "springApi": "canonical JWT sub and owned trip authorization required before location processing",
        "serviceRole": "server-only redaction and retention jobs",
        "fastApi": "no database, JWT or credential access; bounded redacted facts only",
        "crossUserAccess": "forbidden",
    }, "위치정보 접근 정책이 canonical 값과 다릅니다.")
    _require(isinstance(fixture, dict) and set(fixture) == {"contractVersion", "retentionCases"}, "fixture root는 닫힌 객체여야 합니다.")
    _require(canonical_digest(fixture) == CANONICAL_FIXTURE_SHA256, "fixture canonical tree가 변경됐습니다.")
    _require(fixture["contractVersion"] == contract["contractVersion"], "fixture contractVersion이 다릅니다.")
    cases = fixture["retentionCases"]
    _require(isinstance(cases, list) and len(cases) == 8, "retention fixture는 정확히 8개여야 합니다.")
    _require({case.get("policyId") for case in cases if isinstance(case, dict)} == {"trip_execution_event_location", "live_state_current_location", "async_command_location"}, "retention fixture 대상이 다릅니다.")
    for case in cases:
        _require(isinstance(case, dict) and set(case) == {"id", "policyId", "anchors", "evaluatedAt", "expectedCutoff", "expectedDue"}, "retention fixture case는 닫힌 객체여야 합니다.")
        cutoff = retention_cutoff(by_id[case["policyId"]]["retention"], case["anchors"], case["evaluatedAt"])
        _require((cutoff.isoformat() if cutoff else None) == case["expectedCutoff"], "retention cutoff 결과가 다릅니다.")
        _require(is_expired(case["evaluatedAt"], cutoff) is case["expectedDue"], "retention due 결과가 다릅니다.")
    assert_no_sensitive_fixture_values(fixture)


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"JSON을 읽을 수 없습니다: {path}") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Issue #73 위치정보 보존 정책 계약 검증")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    args = parser.parse_args(argv)
    try:
        validate_contract(_load_json(args.contract), _load_json(args.fixture))
    except ValueError as exc:
        print(f"위치정보 보존 정책 계약 검증 실패: {exc}", file=sys.stderr)
        return 1
    print(f"위치정보 보존 정책 계약 검증 성공: {args.contract}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
