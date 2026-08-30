#!/usr/bin/env python3
"""Issue #112 FCM 다음 목적지 출발 알림 계약과 fixture를 검사한다."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import unicodedata
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "docs/contracts/domains/fcm-departure-notification/contract.json"
DEFAULT_FIXTURE = ROOT / "fixtures/contracts/fcm-departure-notification/cases.json"
CANONICAL_CONTRACT_SHA256 = "87ad401723a794b3c1fec5f12507c55f967472afbbc9269a5badf12f144c420a"
CANONICAL_FIXTURE_SHA256 = "c0af781106afe08403c3395a671289bbfd3456da9281d88bd2e84090e5a8c921"
TOP_LEVEL_KEYS = {
    "schemaVersion", "contractVersion", "ownerIssue", "dependencies", "excludedScope",
    "readiness", "ownership", "schedulePolicy", "messagePolicy", "consentPolicy",
    "jobPolicy", "attemptPolicy", "deliveryPolicy", "securityPolicy", "traceability",
}
JOB_STATES = ["PENDING", "LEASED", "RETRY", "ACCEPTED", "CANCELLED", "DEAD"]
JOB_TERMINAL_STATES = ["ACCEPTED", "CANCELLED", "DEAD"]
JOB_TRANSITIONS = [
    {"from": "PENDING", "to": "LEASED", "trigger": "due_claim"},
    {"from": "PENDING", "to": "CANCELLED", "trigger": "pre_claim_invalidation_or_expired"},
    {"from": "LEASED", "to": "ACCEPTED", "trigger": "all_attempts_terminal_and_any_accepted"},
    {"from": "LEASED", "to": "LEASED", "trigger": "expired_lease_exact_reclaim"},
    {"from": "LEASED", "to": "RETRY", "trigger": "retryable_attempt_remains"},
    {"from": "LEASED", "to": "CANCELLED", "trigger": "pre_send_recheck_failed"},
    {"from": "LEASED", "to": "DEAD", "trigger": "all_attempts_terminal_and_none_accepted"},
    {"from": "RETRY", "to": "LEASED", "trigger": "retry_due_claim"},
    {"from": "RETRY", "to": "CANCELLED", "trigger": "retry_invalidation"},
    {"from": "RETRY", "to": "DEAD", "trigger": "expired_or_attempt_limit"},
]
CANCEL_TRIGGER_REASONS = [
    {"trigger": "schedule_version_replaced", "reason": "SCHEDULE_VERSION_REPLACED"},
    {"trigger": "item_completed", "reason": "ITEM_COMPLETED"},
    {"trigger": "item_skipped", "reason": "ITEM_SKIPPED"},
    {"trigger": "trip_cancelled", "reason": "TRIP_CANCELLED"},
    {"trigger": "user_opted_out", "reason": "USER_OPTED_OUT"},
    {"trigger": "preference_changed", "reason": "PREFERENCE_CHANGED"},
    {"trigger": "os_permission_revoked", "reason": "OS_PERMISSION_REVOKED"},
    {"trigger": "location_consent_invalid", "reason": "LOCATION_CONSENT_INVALID"},
    {"trigger": "no_active_push_target", "reason": "NO_ACTIVE_PUSH_TARGET"},
    {"trigger": "expired", "reason": "EXPIRED"},
]
PLATFORM_MAPPING = {
    "android": {"priority": "high", "collapseKeyField": "collapse_key", "collapseKeySource": "canonicalCollapseKey"},
    "apns": {
        "presentation": "alert+sound", "expirationField": "apns-expiration",
        "expirationFormula": "epochSeconds(sendAttemptAt + ttlSeconds)",
        "collapseIdField": "apns-collapse-id", "collapseIdSource": "canonicalCollapseKey",
    },
}
ATTEMPT_STATES = ["RESERVED", "CALL_STARTED", "ACCEPTED", "RETRYABLE_FAILURE", "PERMANENT_FAILURE", "SKIPPED", "ACCEPTANCE_UNKNOWN"]
ATTEMPT_TERMINAL_STATES = ["ACCEPTED", "RETRYABLE_FAILURE", "PERMANENT_FAILURE", "SKIPPED", "ACCEPTANCE_UNKNOWN"]
ATTEMPT_TRANSITIONS = [
    {"from": "RESERVED", "to": "CALL_STARTED", "trigger": "pre_provider_io_marker_cas"},
    {"from": "RESERVED", "to": "RETRYABLE_FAILURE", "trigger": "expired_lease_recovery_proves_no_call"},
    {"from": "CALL_STARTED", "to": "ACCEPTED", "trigger": "provider_message_id_received"},
    {"from": "CALL_STARTED", "to": "RETRYABLE_FAILURE", "trigger": "explicit_transient_rejection_or_provable_pre_connect"},
    {"from": "CALL_STARTED", "to": "PERMANENT_FAILURE", "trigger": "permanent_provider_rejection"},
    {"from": "CALL_STARTED", "to": "ACCEPTANCE_UNKNOWN", "trigger": "ambiguous_result_or_expired_lease_recovery"},
]
CAS_CASES = {
    "all_match_with_unexpired_lease", "job_not_leased", "lease_owner_mismatch", "lease_expired",
    "generation_mismatch", "fencing_token_mismatch", "terminal_job", "attempt_absent",
    "attempt_not_call_started", "target_not_in_flight", "second_completion",
    "retry_after_expiry", "retry_attempt_limit_reached",
}
PROVIDER_OUTCOMES = {
    "provider_message_id_received": ("ACCEPTED", False, False),
    "explicit_transient_rejection": ("RETRYABLE_FAILURE", True, False),
    "provable_pre_connect_failure": ("RETRYABLE_FAILURE", True, False),
    "post_write_ambiguous": ("ACCEPTANCE_UNKNOWN", False, False),
    "permanent_token_rejection": ("PERMANENT_FAILURE", False, True),
    "invalid_payload_config_or_credential": ("PERMANENT_FAILURE", False, False),
    "pre_send_recheck_failed": ("SKIPPED", False, False),
}
FORBIDDEN_FIXTURE_KEYS = {
    "fcmtoken", "registrationtoken", "serviceaccountjson", "privatekey", "clientemail",
    "accesstoken", "refreshtoken", "email", "latitude", "longitude", "coordinates", "memo", "rawpayload",
}
COLLAPSE_KEY_PATTERN = re.compile(
    r"^trip:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):departure$"
)


class DuplicateJsonKeyError(ValueError):
    """JSON object 안의 중복 key를 나타낸다."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _reject_json_constant(token: str) -> Any:
    raise ValueError(f"비표준 JSON 상수 {token}은 허용하지 않습니다.")


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKeyError(f"중복 JSON key는 허용하지 않습니다: {key}")
        result[key] = value
    return result


def _parse_instant(value: Any, field: str) -> datetime:
    _require(isinstance(value, str) and value.strip() == value, f"{field}는 offset이 있는 RFC3339 시각이어야 합니다.")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00").replace("z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"{field}는 offset이 있는 RFC3339 시각이어야 합니다.") from exc
    _require(parsed.tzinfo is not None and parsed.utcoffset() is not None, f"{field}에는 offset이 필요합니다.")
    return parsed


def _has_control(value: str) -> bool:
    return any(unicodedata.category(character) == "Cc" for character in value)


def _canonical_uuid(value: Any, field: str) -> str:
    _require(isinstance(value, str), f"{field}는 문자열 UUID여야 합니다.")
    try:
        parsed = UUID(value)
    except (ValueError, AttributeError) as exc:
        raise ValueError(f"{field}는 canonical lowercase UUID여야 합니다.") from exc
    _require(str(parsed) == value, f"{field}는 canonical lowercase UUID여야 합니다.")
    return value


def canonical_digest(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def evaluate_schedule_case(case: Any, contract: Any) -> dict[str, Any]:
    _require(isinstance(case, dict), "schedule fixture case는 객체여야 합니다.")
    required = {"id", "targetArrivalAt", "expectedTravelDurationSeconds", "tripTimezone", "expected"}
    _require(required <= set(case) <= required | {"safetyBufferMinutes"}, "schedule fixture case는 canonical closed object여야 합니다.")
    schedule = contract["schedulePolicy"]
    _require(set(schedule) == {
        "notifyAtFormula", "scheduledAtAlias", "expiresAtFormula", "persistedInstants", "creationDecision", "displayTime",
        "requiredSources", "sourceSnapshot", "travelDuration", "safetyBuffer", "safetyBufferChange",
        "arrivalInput", "dstOverlapAction", "dstGapAction", "offsetTimezoneMismatchAction",
        "phoneBackgroundExecutionRequired", "recomputeOnActiveVersionChange",
    }, "schedulePolicy는 canonical closed object여야 합니다.")
    _require(schedule["notifyAtFormula"] == "targetArrivalAt - expectedTravelDurationSeconds - safetyBufferMinutes", "notifyAt 공식이 다릅니다.")
    _require(schedule["scheduledAtAlias"] == "notifyAt", "scheduledAt은 notifyAt의 영속 alias여야 합니다.")
    _require(schedule["expiresAtFormula"] == "min(notifyAt + 15 minutes, targetArrivalAt)", "expiresAt 공식이 다릅니다.")
    _require(schedule["persistedInstants"] == {
        "notifyAt": "UTC timestamptz", "scheduledAt": "UTC timestamptz", "expiresAt": "UTC timestamptz",
    }, "예약 시각은 UTC timestamptz로 영속해야 합니다.")
    _require(schedule["creationDecision"] == {
        "evaluatedAtSource": "trusted server/database clock",
        "notifyAtComparator": "notifyAt > evaluatedAt",
        "expiresAtComparator": "expiresAt > evaluatedAt",
        "ineligibleAction": "do_not_create_and_do_not_send",
        "providerCallCount": 0,
    }, "job 생성 시각 decision이 다릅니다.")
    travel_policy = schedule["travelDuration"]
    buffer_policy = schedule["safetyBuffer"]
    travel = case["expectedTravelDurationSeconds"]
    if (
        not isinstance(travel, int) or isinstance(travel, bool)
        or not travel_policy["minimum"] <= travel <= travel_policy["maximum"]
    ):
        return {"action": "fail_closed", "reason": "expectedTravelDurationSeconds is outside the supported integer range"}
    buffer = case.get("safetyBufferMinutes", buffer_policy["default"])
    if (
        not isinstance(buffer, int) or isinstance(buffer, bool)
        or not buffer_policy["minimum"] <= buffer <= buffer_policy["maximum"]
    ):
        return {"action": "fail_closed", "reason": "safetyBufferMinutes must be an integer from 0 through 120"}
    try:
        buffer_seconds = buffer * 60
        _require(buffer_seconds <= 2**63 - 1, "safetyBufferMinutes overflow")
    except (OverflowError, ValueError):
        return {"action": "fail_closed", "reason": "safetyBufferMinutes overflow"}
    try:
        arrival = _parse_instant(case["targetArrivalAt"], "targetArrivalAt")
    except ValueError:
        return {"action": "fail_closed", "reason": "targetArrivalAt must include an offset"}
    try:
        trip_zone = ZoneInfo(case["tripTimezone"])
    except (TypeError, ZoneInfoNotFoundError):
        return {"action": "fail_closed", "reason": "trip timezone is invalid"}

    local_naive = arrival.replace(tzinfo=None)
    valid_offsets = set()
    for fold in (0, 1):
        candidate = local_naive.replace(tzinfo=trip_zone, fold=fold)
        round_trip = candidate.astimezone(timezone.utc).astimezone(trip_zone).replace(tzinfo=None)
        if round_trip == local_naive:
            valid_offsets.add(candidate.utcoffset())
    if not valid_offsets:
        return {"action": "fail_closed", "reason": "targetArrivalAt is a DST gap"}
    if len(valid_offsets) > 1:
        return {"action": "fail_closed", "reason": "targetArrivalAt is a DST overlap"}
    if arrival.utcoffset() not in valid_offsets:
        return {"action": "fail_closed", "reason": "targetArrivalAt offset does not match trip timezone"}
    try:
        notify_at = arrival - timedelta(seconds=travel + buffer_seconds)
        expires_at = min(notify_at + timedelta(minutes=15), arrival)
    except OverflowError:
        return {"action": "fail_closed", "reason": "notifyAt arithmetic overflow"}
    return {
        "action": "schedule",
        "safetyBufferMinutes": buffer,
        "notifyAtUtc": notify_at.astimezone(timezone.utc).isoformat(),
        "expiresAtUtc": expires_at.astimezone(timezone.utc).isoformat(),
        "displayNotifyAt": notify_at.astimezone(trip_zone).isoformat(),
    }


def calculate_ttl_seconds(send_attempt_at: Any, expires_at: Any, maximum_seconds: Any) -> int:
    _require(isinstance(maximum_seconds, int) and not isinstance(maximum_seconds, bool) and maximum_seconds > 0, "TTL maximumSeconds는 양의 정수여야 합니다.")
    attempt = _parse_instant(send_attempt_at, "sendAttemptAt")
    expires = _parse_instant(expires_at, "expiresAt")
    remaining = math.floor((expires.astimezone(timezone.utc) - attempt.astimezone(timezone.utc)).total_seconds())
    return max(0, min(maximum_seconds, remaining))


def evaluate_creation_decision(case: Any, contract: Any) -> dict[str, Any]:
    required = {"id", "evaluatedAt", "notifyAt", "expiresAt", "expected"}
    _require(isinstance(case, dict) and set(case) == required, "creation decision fixture는 closed object여야 합니다.")
    evaluated_at = _parse_instant(case["evaluatedAt"], "evaluatedAt").astimezone(timezone.utc)
    notify_at = _parse_instant(case["notifyAt"], "notifyAt").astimezone(timezone.utc)
    expires_at = _parse_instant(case["expiresAt"], "expiresAt").astimezone(timezone.utc)
    decision = contract["schedulePolicy"]["creationDecision"]
    if notify_at <= evaluated_at or expires_at <= evaluated_at:
        return {"action": decision["ineligibleAction"], "providerCallCount": decision["providerCallCount"]}
    return {"action": "create", "providerCallCount": 0}


def build_collapse_key(trip_id: Any) -> str:
    return f"trip:{_canonical_uuid(trip_id, 'tripId')}:departure"


def validate_collapse_key(value: Any) -> str:
    _require(isinstance(value, str), "collapse key는 문자열이어야 합니다.")
    match = COLLAPSE_KEY_PATTERN.fullmatch(value)
    _require(match is not None, "collapse key는 canonical 형식이어야 합니다.")
    _canonical_uuid(match.group(1), "collapse key tripId")
    return value


def resolve_cancel_reason(trigger: Any, contract: Any) -> str:
    _require(isinstance(trigger, str), "cancel trigger는 문자열이어야 합니다.")
    matches = [item["reason"] for item in contract["jobPolicy"]["cancellationTriggerReasons"] if item["trigger"] == trigger]
    _require(len(matches) == 1, "cancel trigger는 canonical reason 한 개와 일치해야 합니다.")
    return matches[0]


def map_platform_config(case: Any, contract: Any) -> dict[str, Any]:
    required = {"id", "platform", "tripId", "sendAttemptAt", "ttlSeconds", "canonicalCollapseKey", "expected"}
    _require(isinstance(case, dict) and set(case) == required, "platform fixture case는 closed object여야 합니다.")
    attempt = _parse_instant(case["sendAttemptAt"], "sendAttemptAt")
    ttl_seconds = case["ttlSeconds"]
    _require(isinstance(ttl_seconds, int) and not isinstance(ttl_seconds, bool) and 1 <= ttl_seconds <= 900, "ttlSeconds는 1..900 정수여야 합니다.")
    collapse_key = validate_collapse_key(case["canonicalCollapseKey"])
    _require(collapse_key == build_collapse_key(case["tripId"]), "collapse key는 canonical tripId에서 정확히 조립해야 합니다.")
    mapping = contract["messagePolicy"]["platformMapping"]
    if case["platform"] == "ANDROID":
        return {"priority": mapping["android"]["priority"], mapping["android"]["collapseKeyField"]: collapse_key}
    _require(case["platform"] == "IOS", "platform은 ANDROID 또는 IOS여야 합니다.")
    return {
        "presentation": mapping["apns"]["presentation"],
        mapping["apns"]["expirationField"]: int((attempt + timedelta(seconds=ttl_seconds)).timestamp()),
        mapping["apns"]["collapseIdField"]: collapse_key,
    }


def evaluate_consent_case(case: Any) -> dict[str, Any]:
    keys = {"id", "osGranted", "serverEnabled", "requiredVersion", "consentedVersion", "consentStatus", "evaluatedAt", "expected"}
    _require(isinstance(case, dict) and set(case) <= keys and {"id", "expected"} <= set(case), "consent fixture case는 canonical closed object여야 합니다.")
    version_pattern = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    evidence_valid = (
        set(case) == keys
        and type(case.get("osGranted")) is bool
        and type(case.get("serverEnabled")) is bool
        and isinstance(case.get("requiredVersion"), str)
        and version_pattern.fullmatch(case["requiredVersion"]) is not None
        and isinstance(case.get("consentedVersion"), str)
        and version_pattern.fullmatch(case["consentedVersion"]) is not None
        and case.get("consentStatus") in {"ACTIVE", "WITHDRAWN"}
    )
    try:
        if evidence_valid:
            _parse_instant(case.get("evaluatedAt"), "evaluatedAt")
    except ValueError:
        evidence_valid = False
    if not evidence_valid:
        return {"eligible": False, "reason": "invalid_consent_evidence", "auditSnapshot": None}
    snapshot = {
        "documentType": "location",
        "requiredVersion": case["requiredVersion"],
        "consentedVersion": case["consentedVersion"],
        "consentStatus": case["consentStatus"],
        "evaluatedAt": case["evaluatedAt"],
    }
    if not case["osGranted"]:
        reason = "os_permission_not_granted"
    elif not case["serverEnabled"]:
        reason = "server_notification_disabled"
    elif case["consentStatus"] != "ACTIVE":
        reason = "location_consent_inactive"
    elif case["requiredVersion"] != case["consentedVersion"]:
        reason = "location_consent_not_latest"
    else:
        return {"eligible": True, "reason": "eligible", "auditSnapshot": snapshot}
    return {"eligible": False, "reason": reason, "auditSnapshot": snapshot}


def aggregate_target_states(case: Any, contract: Any) -> dict[str, Any]:
    required = {"id", "jobCancelled", "retryEligible", "targetStates", "expected"}
    _require(isinstance(case, dict) and set(case) == required, "aggregation fixture case는 closed object여야 합니다.")
    _require(type(case["jobCancelled"]) is bool and type(case["retryEligible"]) is bool, "aggregation flag는 boolean이어야 합니다.")
    states = case["targetStates"]
    allowed = set(contract["attemptPolicy"]["targetSnapshot"]["states"])
    _require(isinstance(states, list) and bool(states) and all(state in allowed for state in states), "target state가 닫힌 enum과 다릅니다.")
    if case["jobCancelled"]:
        return {"jobState": "CANCELLED", "terminal": True}
    if any(state in {"UNATTEMPTED", "RESERVED", "IN_FLIGHT"} for state in states):
        return {"jobState": "LEASED", "terminal": False}
    if "RETRYABLE" in states and case["retryEligible"]:
        return {"jobState": "RETRY", "terminal": False}
    if "ACCEPTED" in states:
        return {"jobState": "ACCEPTED", "terminal": True}
    return {"jobState": "DEAD", "terminal": True}


def complete_retryable_attempt(case: Any, contract: Any) -> dict[str, Any]:
    required = {"id", "attemptNo", "attemptedAt", "expiresAt", "proposedNextRetryAt", "expected"}
    _require(isinstance(case, dict) and set(case) == required, "retry exhaustion fixture case는 closed object여야 합니다.")
    attempt_no = case["attemptNo"]
    maximum = contract["attemptPolicy"]["retryPolicy"]["maximumAttemptsPerDevice"]
    _require(isinstance(attempt_no, int) and not isinstance(attempt_no, bool) and 1 <= attempt_no <= maximum, "attemptNo가 범위를 벗어났습니다.")
    attempted = _parse_instant(case["attemptedAt"], "attemptedAt")
    expires = _parse_instant(case["expiresAt"], "expiresAt")
    next_retry = _parse_instant(case["proposedNextRetryAt"], "proposedNextRetryAt")
    _require(attempted < expires, "attemptedAt은 expiresAt 전이어야 합니다.")
    retry_allowed = attempt_no < maximum and next_retry < expires
    return {
        "attemptStatus": "RETRYABLE_FAILURE",
        "attemptPersisted": True,
        "jobState": "RETRY" if retry_allowed else "DEAD",
        "nextRetryAt": case["proposedNextRetryAt"] if retry_allowed else None,
        "atomic": True,
    }


def evaluate_buffer_change_case(case: Any, contract: Any) -> dict[str, Any]:
    required = {
        "id", "oldBufferMinutes", "newBufferMinutes", "expectedPreferenceVersion", "actualPreferenceVersion",
        "targetArrivalAt", "expectedTravelDurationSeconds", "tripTimezone", "expected",
    }
    _require(isinstance(case, dict) and set(case) == required, "buffer change fixture case는 closed object여야 합니다.")
    for field in ("oldBufferMinutes", "newBufferMinutes", "expectedPreferenceVersion", "actualPreferenceVersion"):
        _require(isinstance(case[field], int) and not isinstance(case[field], bool), f"{field}는 정수여야 합니다.")
    if case["expectedPreferenceVersion"] != case["actualPreferenceVersion"]:
        return {"action": "fail_closed", "reason": "preference_version_conflict", "mutationApplied": False}
    scheduled = evaluate_schedule_case({
        "id": case["id"],
        "targetArrivalAt": case["targetArrivalAt"],
        "expectedTravelDurationSeconds": case["expectedTravelDurationSeconds"],
        "safetyBufferMinutes": case["newBufferMinutes"],
        "tripTimezone": case["tripTimezone"],
        "expected": {},
    }, contract)
    _require(scheduled["action"] == "schedule", "새 safetyBufferMinutes로 예약할 수 없습니다.")
    return {
        "action": "replace_atomically",
        "newPreferenceVersion": case["actualPreferenceVersion"] + 1,
        "newNotifyAtUtc": scheduled["notifyAtUtc"],
        "oldGenerationInvalidated": True,
        "oldUnsentJobCancelled": True,
    }


def evaluate_lease_reclaim_case(case: Any, contract: Any) -> dict[str, Any]:
    required = {
        "id", "state", "expectedState", "databaseNow", "leaseUntil", "expectedLeaseUntil", "currentOwner",
        "expectedOwner", "newOwner", "newLeaseUntil", "generation", "expectedGeneration",
        "currentFencingToken", "expectedFencingToken", "targetSnapshot", "attemptRows", "expected",
    }
    _require(isinstance(case, dict) and set(case) == required, "lease reclaim fixture case는 closed object여야 합니다.")
    _require(case["state"] == "LEASED", "expired reclaim은 LEASED job만 허용합니다.")
    for field in ("generation", "currentFencingToken", "expectedFencingToken"):
        _require(isinstance(case[field], int) and not isinstance(case[field], bool) and case[field] >= 0, f"{field}는 음이 아닌 정수여야 합니다.")
    _require(isinstance(case["currentOwner"], str) and case["currentOwner"] and isinstance(case["newOwner"], str) and case["newOwner"], "lease owner가 필요합니다.")
    now = _parse_instant(case["databaseNow"], "databaseNow")
    lease_until = _parse_instant(case["leaseUntil"], "leaseUntil")
    new_lease_until = _parse_instant(case["newLeaseUntil"], "newLeaseUntil")
    _require(isinstance(case["targetSnapshot"], list) and isinstance(case["attemptRows"], list), "reclaim identity snapshot은 배열이어야 합니다.")
    if (
        case["state"] != case["expectedState"]
        or case["currentOwner"] != case["expectedOwner"]
        or case["leaseUntil"] != case["expectedLeaseUntil"]
        or case["generation"] != case["expectedGeneration"]
    ):
        return {"action": "reject", "reason": "stale_reclaim_cas", "mutationApplied": False}
    if case["currentFencingToken"] != case["expectedFencingToken"]:
        return {"action": "reject", "reason": "stale_fencing_token", "mutationApplied": False}
    if lease_until > now:
        return {"action": "reject", "reason": "lease_not_expired", "mutationApplied": False}
    _require(new_lease_until > now, "새 leaseUntil은 databaseNow 뒤여야 합니다.")
    return {
        "action": "reclaim",
        "state": "LEASED",
        "leaseOwner": case["newOwner"],
        "leaseUntil": case["newLeaseUntil"],
        "generation": case["generation"],
        "fencingToken": case["currentFencingToken"] + 1,
        "targetSnapshot": list(case["targetSnapshot"]),
        "attemptRows": list(case["attemptRows"]),
    }


def evaluate_target_race_case(case: Any, contract: Any) -> dict[str, Any]:
    required = {
        "id", "phase", "claimActiveDevices", "preparationActiveDevices", "snapshotDevices", "deviceToCall",
        "deviceActiveBeforeCall", "jobWideEligibleBeforeCall", "expected",
    }
    _require(isinstance(case, dict) and set(case) == required, "target race fixture case는 closed object여야 합니다.")
    device_lists: dict[str, list[str]] = {}
    for field in ("claimActiveDevices", "preparationActiveDevices", "snapshotDevices"):
        values = case[field]
        _require(isinstance(values, list), f"{field}는 배열이어야 합니다.")
        canonical = [_canonical_uuid(value, field) for value in values]
        _require(len(canonical) == len(set(canonical)), f"{field}에 중복 device가 있습니다.")
        device_lists[field] = canonical
    _require(type(case["jobWideEligibleBeforeCall"]) is bool, "job-wide eligibility는 boolean이어야 합니다.")
    phase = case["phase"]
    if phase == "preparation":
        _require(case["deviceToCall"] is None and case["deviceActiveBeforeCall"] is None, "preparation에는 call target이 없어야 합니다.")
        if not device_lists["preparationActiveDevices"]:
            return {
                "action": "CANCELLED", "reason": "NO_ACTIVE_PUSH_TARGET", "snapshotDevices": [],
                "attemptCount": 0, "providerCallCount": 0, "atomic": True,
            }
        return {"action": "store_snapshot", "snapshotDevices": device_lists["preparationActiveDevices"]}
    if phase == "post_snapshot_activation":
        _canonical_uuid(case["deviceToCall"], "deviceToCall")
        return {"action": "keep_snapshot", "snapshotDevices": device_lists["snapshotDevices"]}
    _require(phase == "before_call", "target race phase가 닫힌 enum과 다릅니다.")
    device = _canonical_uuid(case["deviceToCall"], "deviceToCall")
    _require(device in device_lists["snapshotDevices"], "호출 대상은 snapshot target이어야 합니다.")
    _require(type(case["deviceActiveBeforeCall"]) is bool, "device active recheck는 boolean이어야 합니다.")
    if not case["jobWideEligibleBeforeCall"]:
        return {"action": "CANCELLED", "providerCall": False, "stopRemainingCalls": True}
    if not case["deviceActiveBeforeCall"]:
        return {"action": "SKIPPED", "providerCall": False}
    return {"action": "reserve_attempt", "providerCall": True}


def evaluate_completion_case(case: Any, contract: Any) -> dict[str, Any]:
    required = {
        "id", "attemptExists", "attemptStatus", "targetState", "completionAlreadyApplied",
        "casMatches", "resultStatus", "expected",
    }
    _require(isinstance(case, dict) and set(case) == required, "completion fixture case는 closed object여야 합니다.")
    _require(type(case["attemptExists"]) is bool and type(case["completionAlreadyApplied"]) is bool and type(case["casMatches"]) is bool, "completion flag는 boolean이어야 합니다.")
    if not case["casMatches"]:
        return {"action": "reject", "reason": "cas_mismatch", "mutationApplied": False}
    if not case["attemptExists"]:
        return {"action": "reject", "reason": "absent_attempt", "mutationApplied": False}
    if case["completionAlreadyApplied"]:
        return {"action": "reject", "reason": "second_completion", "mutationApplied": False}
    if case["attemptStatus"] == "RESERVED":
        return {"action": "reject", "reason": "reserved_attempt", "mutationApplied": False}
    if case["attemptStatus"] in ATTEMPT_TERMINAL_STATES:
        return {"action": "reject", "reason": "terminal_attempt", "mutationApplied": False}
    if case["attemptStatus"] != "CALL_STARTED" or case["targetState"] != "IN_FLIGHT":
        return {"action": "reject", "reason": "wrong_target_marker", "mutationApplied": False}
    _require(case["resultStatus"] in [status for status in ATTEMPT_TERMINAL_STATES if status != "SKIPPED"], "completion result status가 다릅니다.")
    target_state = "RETRYABLE" if case["resultStatus"] == "RETRYABLE_FAILURE" else case["resultStatus"]
    return {"action": "apply", "attemptStatus": case["resultStatus"], "targetState": target_state, "jobAggregationRecomputed": True, "atomic": True}


def evaluate_target_transition_case(case: Any, contract: Any) -> dict[str, Any]:
    required = {"id", "fromState", "toState", "currentAttemptNo", "requestedAttemptNo", "attemptKeyExists", "expected"}
    _require(isinstance(case, dict) and set(case) == required, "target transition fixture case는 closed object여야 합니다.")
    _require(type(case["attemptKeyExists"]) is bool, "attemptKeyExists는 boolean이어야 합니다.")
    for field in ("currentAttemptNo", "requestedAttemptNo"):
        _require(isinstance(case[field], int) and not isinstance(case[field], bool) and case[field] >= 0, f"{field}는 음이 아닌 정수여야 합니다.")
    policy = contract["attemptPolicy"]["targetTransitionPolicy"]
    allowed = {(item["from"], item["to"]) for item in policy["allowedTransitions"]}
    allowed |= {(item["from"], item["to"]) for item in policy["recoveryTransitions"]}
    transition = (case["fromState"], case["toState"])
    inserts_attempt = case["toState"] in {"RESERVED", "SKIPPED"}
    if inserts_attempt and case["attemptKeyExists"]:
        return {"action": "reject", "reason": "duplicate_reservation_key", "providerCall": False}
    if transition not in allowed:
        return {"action": "reject", "reason": "invalid_target_transition", "providerCall": False}
    if inserts_attempt and case["requestedAttemptNo"] != case["currentAttemptNo"] + 1:
        return {"action": "reject", "reason": "attempt_number_not_incremented", "providerCall": False}
    new_attempt_no = case["requestedAttemptNo"] if inserts_attempt else case["currentAttemptNo"]
    return {"action": "apply", "targetState": case["toState"], "currentAttemptNo": new_attempt_no, "atomic": True}


def evaluate_retry_inactive_skip_case(case: Any, contract: Any) -> dict[str, Any]:
    required = {"id", "currentAttemptNo", "requestedAttemptNo", "attemptKeyExists", "casMatches", "deviceActive", "expected"}
    _require(isinstance(case, dict) and set(case) == required, "retry inactive skip fixture case는 closed object여야 합니다.")
    _require(type(case["attemptKeyExists"]) is bool and type(case["casMatches"]) is bool and type(case["deviceActive"]) is bool, "retry inactive skip flag는 boolean이어야 합니다.")
    _require(isinstance(case["currentAttemptNo"], int) and not isinstance(case["currentAttemptNo"], bool) and case["currentAttemptNo"] >= 1, "currentAttemptNo는 양의 정수여야 합니다.")
    _require(isinstance(case["requestedAttemptNo"], int) and not isinstance(case["requestedAttemptNo"], bool), "requestedAttemptNo는 정수여야 합니다.")
    _require(case["deviceActive"] is False, "retry inactive skip은 비활성 device에만 적용합니다.")
    if not case["casMatches"]:
        return {"action": "reject", "reason": "stale_cas", "mutationApplied": False, "providerCallCount": 0}
    if case["attemptKeyExists"]:
        return {"action": "reject", "reason": "duplicate_attempt_key", "mutationApplied": False, "providerCallCount": 0}
    _require(case["requestedAttemptNo"] == case["currentAttemptNo"] + 1, "retry SKIPPED attemptNo는 current+1이어야 합니다.")
    return {
        "action": "apply", "attemptNo": case["requestedAttemptNo"], "attemptStatus": "SKIPPED",
        "targetState": "SKIPPED", "jobAggregationRecomputed": True, "providerCallCount": 0, "atomic": True,
    }


def validate_data_payload(payload: Any, contract: Any) -> None:
    schema = contract["messagePolicy"]["dataSchema"]
    _require(isinstance(payload, dict), "FCM data는 객체여야 합니다.")
    required = schema["required"]
    _require(set(payload) == set(required), "FCM data는 required key만 가진 closed object여야 합니다.")
    validate_utf8_budgets(payload, schema)
    for key, value in payload.items():
        _require(isinstance(key, str) and isinstance(value, str), "FCM data key와 value는 모두 문자열이어야 합니다.")
        _require(not _has_control(key) and not _has_control(value), "FCM data에는 control character를 넣을 수 없습니다.")
    _require(payload["contractVersion"] == contract["contractVersion"], "FCM data contractVersion이 다릅니다.")
    trip_id = _canonical_uuid(payload["tripId"], "tripId")
    item_id = _canonical_uuid(payload["tripItemId"], "tripItemId")
    _canonical_uuid(payload["scheduleVersionId"], "scheduleVersionId")
    expected_link = f"timingjeju://trips/{trip_id}/live?itemId={item_id}"
    _require("%" not in payload["deepLink"], "deepLink percent encoding은 허용하지 않습니다.")
    _require(payload["deepLink"] == expected_link, "deepLink는 canonical UUID template과 정확히 일치해야 합니다.")


def validate_utf8_budgets(payload: Any, schema: Any) -> None:
    _require(isinstance(payload, dict), "FCM data는 객체여야 합니다.")
    total = 0
    for key, value in payload.items():
        _require(isinstance(key, str) and isinstance(value, str), "FCM data key와 value는 모두 문자열이어야 합니다.")
        key_bytes = len(key.encode("utf-8"))
        value_bytes = len(value.encode("utf-8"))
        _require(key_bytes <= schema["maxKeyUtf8Bytes"], "FCM data key UTF-8 byte 제한을 초과했습니다.")
        _require(value_bytes <= schema["maxValueUtf8Bytes"], "FCM data value UTF-8 byte 제한을 초과했습니다.")
        total += key_bytes + value_bytes
    _require(total <= schema["maxTotalUtf8Bytes"], "FCM data 전체 UTF-8 byte 제한을 초과했습니다.")


def build_notification(case: Any, contract: Any) -> dict[str, str]:
    _require(isinstance(case, dict), "message fixture case는 객체여야 합니다.")
    _require(set(case) == {"id", "departureRecommendedLocalTime", "nextPlaceName", "data", "expectedNotification"}, "message fixture case는 canonical closed object여야 합니다.")
    validate_data_payload(case["data"], contract)
    notification = contract["messagePolicy"]["notification"]
    title = notification["titleTemplate"]
    body = notification["bodyTemplate"].format(
        departureRecommendedLocalTime=case["departureRecommendedLocalTime"],
        nextPlaceName=case["nextPlaceName"],
    )
    invalid = (
        not isinstance(case["departureRecommendedLocalTime"], str)
        or not isinstance(case["nextPlaceName"], str)
        or _has_control(title)
        or _has_control(body)
        or len(title.encode("utf-8")) > notification["titleMaxUtf8Bytes"]
        or len(body.encode("utf-8")) > notification["bodyMaxUtf8Bytes"]
    )
    return dict(notification["fallback"]) if invalid else {"title": title, "body": body}


def build_attempt_keys(job_id: Any, device_ids: Any, attempt_no: Any) -> list[tuple[str, str, int]]:
    job = _canonical_uuid(job_id, "jobId")
    _require(isinstance(device_ids, list) and bool(device_ids), "pushDeviceId 목록이 필요합니다.")
    _require(isinstance(attempt_no, int) and not isinstance(attempt_no, bool) and attempt_no > 0, "attemptNo는 양의 정수여야 합니다.")
    keys = [(job, _canonical_uuid(device, "pushDeviceId"), attempt_no) for device in device_ids]
    _require(len(keys) == len(set(keys)), "중복 delivery attempt key는 허용하지 않습니다.")
    return keys


def assert_no_sensitive_values(value: Any) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            _require(isinstance(key, str), "fixture key는 문자열이어야 합니다.")
            if key.casefold() in FORBIDDEN_FIXTURE_KEYS:
                raise ValueError(f"민감정보 fixture key는 금지됩니다: {key}")
            assert_no_sensitive_values(child)
    elif isinstance(value, list):
        for child in value:
            assert_no_sensitive_values(child)
    elif isinstance(value, float):
        _require(math.isfinite(value), "fixture non-finite number는 금지됩니다.")


def validate_contract(contract: Any, fixture: Any) -> None:
    _require(isinstance(contract, dict), "계약 root는 객체여야 합니다.")
    _require(set(contract) == TOP_LEVEL_KEYS, "계약 root는 canonical closed object여야 합니다.")
    _require(contract["schemaVersion"] == "timing-jeju-fcm-departure-notification/v1", "schemaVersion이 다릅니다.")
    _require(contract["contractVersion"] == "1.0.0", "contractVersion이 다릅니다.")
    _require(contract["ownerIssue"] == 112 and not isinstance(contract["ownerIssue"], bool), "ownerIssue는 112여야 합니다.")
    _require(contract["dependencies"] == [72, 73, 93], "선행 계약 목록이 다릅니다.")

    readiness = contract["readiness"]
    _require(readiness["contractReady"] is True and readiness["implementationReady"] is False, "계약과 구현 readiness를 분리해야 합니다.")
    _require(readiness["productionDefaultEnabled"] is False and readiness["missingPreconditionAction"] == "fail_closed", "미구현 알림은 fail-closed여야 합니다.")
    ownership = contract["ownership"]
    _require(ownership["publicApiAddedByIssue"] == "none" and ownership["databaseMigrationAddedByIssue"] == "none", "#112는 API나 migration을 추가하지 않습니다.")
    _require(ownership["springOwns"] == ["token registry", "logical job", "per-device attempt", "cancel", "dispatch", "retry", "provider acceptance state"], "Spring 소유권이 다릅니다.")
    _require(ownership["fastApiForbidden"] == ["token", "logical job", "per-device attempt", "cancel", "dispatch", "retry", "notification state", "FCM credential"], "FastAPI 비관여 경계가 다릅니다.")

    schedule = contract["schedulePolicy"]
    _require(schedule["safetyBuffer"] == {
        "field": "safetyBufferMinutes", "unit": "minutes", "default": 10, "minimum": 0, "maximum": 120,
        "inclusiveBounds": True, "integerOnly": True, "zeroAllowed": True, "checkedSecondsConversion": True,
        "overflowAction": "fail_closed",
    }, "safetyBufferMinutes 계약이 다릅니다.")
    _require(schedule["safetyBufferChange"] == {
        "trigger": "safetyBufferMinutes changed",
        "atomicOrder": ["compare preference version", "invalidate old generation", "cancel old unsent job", "persist new preference", "recompute notifyAt", "create new logical job or record expired omission"],
        "concurrentVersionMismatchAction": "fail_closed_without_mutation",
        "alreadyCallStartedAction": "preserve attempt evidence and recover as ACCEPTANCE_UNKNOWN",
    }, "safetyBufferMinutes 변경 계약이 다릅니다.")
    _require(schedule["dstOverlapAction"] == "fail_closed_even_when_either_offset_is_supplied", "DST overlap은 양쪽 offset 모두 fail-closed여야 합니다.")
    _require(schedule["dstGapAction"] == "fail_closed", "DST gap은 fail-closed여야 합니다.")

    message = contract["messagePolicy"]
    _require(set(message) == {
        "messageType", "androidPriority", "apnsPresentation", "platformMapping", "notification",
        "dataSchema", "forbiddenContent", "collapseKeyTemplate", "collapseKeyValidation", "ttlPolicy",
    }, "messagePolicy는 canonical closed object여야 합니다.")
    _require(message["messageType"] == "notification+data" and message["androidPriority"] == "high", "사용자 표시 notification+data가 필요합니다.")
    _require(message["platformMapping"] == PLATFORM_MAPPING, "Android/APNs provider mapping이 다릅니다.")
    _require(message["collapseKeyTemplate"] == "trip:{tripId}:departure" and message["collapseKeyValidation"] == {
        "source": "canonical lowercase tripId",
        "pattern": "^trip:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:departure$",
        "validation": "regex plus UUID roundtrip",
    }, "collapse key canonical validation이 다릅니다.")
    schema = message["dataSchema"]
    _require(schema["required"] == ["contractVersion", "tripId", "tripItemId", "scheduleVersionId", "deepLink"], "FCM data required가 다릅니다.")
    _require(schema["additionalProperties"] is False, "FCM data는 closed object여야 합니다.")
    _require((schema["maxKeyUtf8Bytes"], schema["maxValueUtf8Bytes"], schema["maxTotalUtf8Bytes"]) == (64, 512, 2048), "FCM data UTF-8 byte budget이 다릅니다.")
    _require(message["ttlPolicy"] == {"maximumSeconds": 900, "remainingUntil": "expiresAt", "calculation": "min(900, floor(expiresAt - sendAttemptAt))", "nonPositiveAction": "do_not_send"}, "TTL 계약이 다릅니다.")

    consent = contract["consentPolicy"]
    _require(consent["requiredSignals"] == ["osNotificationPermissionGranted", "serverDepartureNotificationEnabled", "latestRequiredLocationConsent"], "OS·서버·최신 위치 동의가 필요합니다.")
    _require(consent["checkpoints"] == ["atSchedule", "immediatelyBeforeSend"], "동의는 예약과 발송 직전에 검사해야 합니다.")
    _require(consent["locationConsentEvaluation"]["requiredVersionSource"] == "latest effective required document at evaluation instant", "최신 required 위치 문서 조회가 필요합니다.")
    _require(consent["locationConsentEvaluation"]["canonicalVersionPattern"] == "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", "동의 버전 형식이 다릅니다.")
    _require(consent["locationConsentEvaluation"]["allowedStatuses"] == ["ACTIVE", "WITHDRAWN"], "동의 상태 enum이 다릅니다.")

    job = contract["jobPolicy"]
    _require(set(job) == {
        "persistenceOwnerIssue", "table", "logicalJobKey", "deviceIdentityIncluded",
        "duplicateLogicalJobAction", "states", "terminalStates", "allowedTransitions", "cancelReasons",
        "cancellationTriggerReasons", "preSendRecheck", "claimRequirements", "expiredLeaseReclaim",
        "staleWorkerDefense", "scheduleReplacementOrder",
    }, "jobPolicy는 canonical closed object여야 합니다.")
    _require(job["persistenceOwnerIssue"] == 115 and job["table"] == "notification_jobs", "logical job persistence owner가 다릅니다.")
    _require(job["logicalJobKey"] == ["tripId", "scheduleVersionId", "tripItemId", "tripLegId", "notificationType", "scheduledAt"], "logical job key가 다릅니다.")
    _require(job["deviceIdentityIncluded"] is False, "logical job key에 device가 들어가면 안 됩니다.")
    _require(job["states"] == JOB_STATES and job["terminalStates"] == JOB_TERMINAL_STATES, "job 상태가 다릅니다.")
    _require(job["allowedTransitions"] == JOB_TRANSITIONS, "job transition matrix가 다릅니다.")
    _require(not any(item["from"] in JOB_TERMINAL_STATES for item in job["allowedTransitions"]), "terminal job은 전이할 수 없습니다.")
    _require(job["cancelReasons"] == [item["reason"] for item in CANCEL_TRIGGER_REASONS], "cancelReason enum이 다릅니다.")
    _require(job["cancellationTriggerReasons"] == CANCEL_TRIGGER_REASONS, "취소 trigger→reason mapping이 다릅니다.")
    _require(job["expiredLeaseReclaim"] == {
        "fromState": "LEASED", "toState": "LEASED",
        "precondition": "leaseUntil is at or before database now and exact current CAS matches",
        "casKey": ["expectedState", "expectedLeaseOwner", "expectedLeaseUntil", "expectedGeneration", "expectedFencingToken"],
        "preserve": ["generation", "targetSnapshot", "attemptRows"],
        "replace": ["leaseOwner", "leaseUntil"], "fencingToken": "increment", "oldFenceCompletion": "reject",
    }, "expired LEASED same-state reclaim 계약이 다릅니다.")

    attempt = contract["attemptPolicy"]
    _require(attempt["persistenceOwnerIssue"] == 116 and attempt["table"] == "push_delivery_attempts", "attempt persistence owner가 다릅니다.")
    _require(attempt["deliveryAttemptKey"] == ["jobId", "pushDeviceId", "attemptNo"], "delivery attempt key가 다릅니다.")
    _require(attempt["states"] == ATTEMPT_STATES and attempt["terminalStates"] == ATTEMPT_TERMINAL_STATES, "attempt 상태와 terminality가 다릅니다.")
    _require(attempt["attemptStatusTransitions"] == ATTEMPT_TRANSITIONS, "attempt same-row transition matrix가 다릅니다.")
    _require(attempt["directTerminalInsert"] == "SKIPPED only after immediately-before-call recheck fails and before reservation", "SKIPPED 저장 경계가 다릅니다.")
    _require(attempt["terminalMutationAction"] == "reject_without_overwrite", "terminal attempt는 immutable이어야 합니다.")
    _require(attempt["dispatchReservation"] == {
        "inFlightStates": ["RESERVED", "CALL_STARTED"],
        "reservationCasKey": ["jobId", "pushDeviceId", "attemptNo", "leaseOwner", "generation", "fencingToken"],
        "beforeProviderCall": "insert RESERVED exact attempt key and commit",
        "callStartMarker": "CAS RESERVED to CALL_STARTED and commit before any provider I/O",
        "reservationOrMarkerCasFailure": "provider call forbidden",
        "statusStorage": "single push_delivery_attempts row",
        "statusMutation": "same row CAS",
        "terminalImmutable": True,
    }, "provider call 전 durable reservation 계약이 다릅니다.")
    target = attempt["targetSnapshot"]
    _require(target["table"] == "push_delivery_targets" and target["key"] == ["jobId", "pushDeviceId"], "target snapshot persistence mapping이 다릅니다.")
    _require(target["closedFields"] == ["jobId", "pushDeviceId", "ordinal", "currentState", "currentAttemptNo", "capturedAt"], "target snapshot field가 닫혀 있지 않습니다.")
    _require(target["states"] == ["UNATTEMPTED", "RESERVED", "IN_FLIGHT", "RETRYABLE", "ACCEPTED", "ACCEPTANCE_UNKNOWN", "PERMANENT_FAILURE", "SKIPPED"], "target current state enum이 다릅니다.")
    _require(target["capturePoint"] == "claim commit 후 첫 pre-send preparation transaction에서 live eligibility 재검사 후 저장" and target["claimTransactionStoresTargets"] is False, "target snapshot capture transaction이 다릅니다.")
    _require(target["preparationCas"] == ["leaseOwner", "generation", "fencingToken", "unexpiredLeaseUntil"], "preparation CAS가 다릅니다.")
    outcomes = attempt["providerOutcomeMatrix"]
    _require(len(outcomes) == len(PROVIDER_OUTCOMES) and {item["outcome"] for item in outcomes} == set(PROVIDER_OUTCOMES), "provider outcome matrix가 닫혀 있지 않습니다.")
    for item in outcomes:
        _require(set(item) == {"outcome", "attemptStatus", "terminal", "retryAllowed", "invalidateDevice"}, "provider outcome은 closed object여야 합니다.")
        status, retry, invalidate = PROVIDER_OUTCOMES[item["outcome"]]
        _require((item["attemptStatus"], item["terminal"], item["retryAllowed"], item["invalidateDevice"]) == (status, True, retry, invalidate), f"provider outcome이 다릅니다: {item['outcome']}")
    _require(attempt["postWriteAmbiguity"] == {"transportExamples": ["write_timeout", "read_timeout", "connection_reset", "unexpected_eof"], "attemptStatus": "ACCEPTANCE_UNKNOWN", "retryAllowed": False}, "post-write ambiguity 범주가 다릅니다.")
    _require(attempt["provablePreConnectBoundary"] == "request bytes provably not sent", "pre-connect 경계가 다릅니다.")
    crash = attempt["crashRecoveryMatrix"]
    crash_cases = {"crash_before_call_marker", "crash_after_call_marker_before_write", "crash_after_write_before_result", "crash_after_result_before_completion", "expired_lease_reserved", "expired_lease_call_started"}
    _require(len(crash) == len(crash_cases) and {item["case"] for item in crash} == crash_cases, "crash recovery matrix가 완전하지 않습니다.")
    _require(all(set(item) == {"case", "observedMarker", "attemptStatus", "retryAllowed", "recoveryAction", "sameAttemptRow", "requiredCas"} for item in crash), "crash recovery case는 closed object여야 합니다.")
    _require(all(item["sameAttemptRow"] is True and item["requiredCas"] == "current owner/generation/fencing/unexpired lease CAS" for item in crash), "crash recovery는 reclaimed fence의 same row CAS여야 합니다.")
    _require(attempt["targetRacePolicy"] == {
        "immediatelyBeforeEachTargetCall": ["user setting", "OS permission", "latest required location consent", "device active"],
        "claimToPreparationActivation": "include when active at preparation",
        "claimToPreparationDeactivation": "exclude when inactive at preparation",
        "postSnapshotActivation": "do not add to current job",
        "postSnapshotDeviceDeactivation": "SKIPPED without provider call",
        "postSnapshotJobWideInvalidation": "CANCELLED and stop remaining calls",
    }, "claim/preparation/post-snapshot race 계약이 다릅니다.")
    _require(attempt["zeroEligibleTargetPolicy"] == {
        "snapshot": "persist empty snapshot", "jobState": "CANCELLED", "cancelReason": "NO_ACTIVE_PUSH_TARGET",
        "attemptCount": 0, "providerCallCount": 0, "transaction": "same preparation transaction",
    }, "eligible target 0건 계약이 다릅니다.")
    _require(attempt["targetTransitionPolicy"] == {
        "allowedTransitions": [
            {"from": "UNATTEMPTED", "to": "RESERVED"}, {"from": "RETRYABLE", "to": "RESERVED"},
            {"from": "RESERVED", "to": "IN_FLIGHT"}, {"from": "IN_FLIGHT", "to": "ACCEPTED"},
            {"from": "IN_FLIGHT", "to": "RETRYABLE"}, {"from": "IN_FLIGHT", "to": "ACCEPTANCE_UNKNOWN"},
            {"from": "IN_FLIGHT", "to": "PERMANENT_FAILURE"}, {"from": "UNATTEMPTED", "to": "SKIPPED"},
            {"from": "RETRYABLE", "to": "SKIPPED"},
        ],
        "recoveryTransitions": [
            {"from": "RESERVED", "to": "RETRYABLE", "trigger": "expired lease proves provider call not started"},
            {"from": "IN_FLIGHT", "to": "ACCEPTANCE_UNKNOWN", "trigger": "expired lease after call marker"},
        ],
        "retryReservationAttemptNo": "currentAttemptNo + 1 exact key",
        "duplicateReservationConstraint": "unique(jobId,pushDeviceId,attemptNo)",
        "duplicateReservationAction": "reject_without_provider_call",
        "sameCasTransaction": ["attempt row", "target row", "job aggregation"],
    }, "target transition 계약이 다릅니다.")
    _require(attempt["retryInactiveSkipPolicy"] == {
        "precondition": "RETRYABLE and inactive immediately before call",
        "newAttemptNo": "currentAttemptNo + 1", "attemptStatus": "SKIPPED",
        "targetTransition": "RETRYABLE to SKIPPED",
        "sameCasTransaction": ["attempt insert", "target transition", "job aggregation"],
        "providerCallCount": 0,
        "duplicateOrStaleAction": "reject_without_mutation_or_provider_call",
    }, "inactive retry target SKIPPED 계약이 다릅니다.")
    _require(attempt["completionPolicy"] == {
        "existingExactAttemptRequired": True, "requiredAttemptStatus": "CALL_STARTED", "requiredTargetState": "IN_FLIGHT",
        "sameTransaction": ["attempt terminal CAS", "target transition", "job aggregation"],
        "rejectionCases": ["absent_attempt", "reserved_attempt", "terminal_attempt", "wrong_target_marker", "second_completion", "cas_mismatch"],
    }, "completion existing marker gate가 다릅니다.")
    matrix = attempt["completionCasMatrix"]
    _require(len(matrix) == len(CAS_CASES) and {item["case"] for item in matrix} == CAS_CASES, "completion CAS matrix가 완전하지 않습니다.")
    _require(all(set(item) == {"case", "condition", "action", "writesAllowed"} for item in matrix), "completion CAS case는 closed object여야 합니다.")
    _require(attempt["retryPolicy"] == {"maximumAttemptsPerDevice": 3, "retryAfterPrecedence": True, "backoff": "bounded exponential backoff with full jitter", "maximumDelaySeconds": 60, "mustRemainBeforeExpiresAt": True, "acceptanceUnknownRetry": "forbidden"}, "retry 계약이 다릅니다.")
    by_cas_case = {item["case"]: item for item in matrix}
    for case_name in ("retry_after_expiry", "retry_attempt_limit_reached"):
        _require(by_cas_case[case_name]["writesAllowed"] is True and by_cas_case[case_name]["action"] == "persist_current_retryable_attempt_and_job_dead_atomically", "소진된 transient attempt를 유실하면 안 됩니다.")

    delivery = contract["deliveryPolicy"]
    _require(delivery["providerMessageIdMeaning"] == "accepted" and delivery["deliveredClaimAllowed"] is False, "FCM 접수와 전달을 구분해야 합니다.")
    security = contract["securityPolicy"]
    _require(security["registrationTokenClassification"] == "sensitive device identifier", "FCM token은 민감 기기 식별값입니다.")
    _require(security["firebaseCredentialInjection"] == "ADC or secret mount only" and security["firebaseCredentialRepositoryStorage"] == "forbidden", "Firebase credential 경계가 다릅니다.")
    _require({key.casefold() for key in security["fixtureForbiddenKeys"]} == FORBIDDEN_FIXTURE_KEYS, "fixture 민감 key denylist가 다릅니다.")
    traceability = contract["traceability"]
    _require("pmIssueAmendmentsRequired" not in traceability and set(traceability) == {"futureIssueOwners", "issueReadbackEvidence"}, "Issue readback 추적성이 닫혀 있지 않습니다.")
    expected_evidence = {
        113: ("2026-08-28T20:11:58Z", ["default 10 and integer 0..120 inclusive", "latest required location consent"]),
        114: ("2026-08-28T23:50:45Z", ["closed FCM data UTF-8 budgets", "provable pre-connect versus post-write/read ambiguity", "unexpected EOF is post-write ambiguous"]),
        115: ("2026-08-26T03:57:27Z", ["device-independent logical job key", "one logical job per notification", "safetyBuffer version-CAS atomic replacement"]),
        116: ("2026-08-26T04:33:51Z", ["exact per-device attempt key", "ACCEPTANCE_UNKNOWN no retry", "lease generation fencing", "push_delivery_targets closed snapshot and current states", "RESERVED/CALL_STARTED durable pre-I/O protocol", "marker-based crash and expired-lease recovery", "mutually exclusive target aggregation precedence", "exhausted transient attempt persistence", "single-row RESERVED/CALL_STARTED/terminal status lifecycle", "expired LEASED same-state reclaim with preserved generation and incremented fence", "post-claim preparation snapshot and claim/post-snapshot race rechecks", "existing CALL_STARTED plus IN_FLIGHT completion gate", "zero-target preparation cancellation", "closed target transitions and atomic attempt-target-job aggregation", "single generation naming", "inactive retry target terminal SKIPPED attempt"]),
    }
    evidence = traceability["issueReadbackEvidence"]
    _require(len(evidence) == 4 and {item["issue"] for item in evidence} == set(expected_evidence), "Issue readback 대상이 다릅니다.")
    for item in evidence:
        expected_keys = {"issue", "updatedAt", "appliedMarkers", "blankLineNormalizedBodySha256"} if item["issue"] == 116 else {"issue", "updatedAt", "appliedMarkers"}
        _require(set(item) == expected_keys and (item["updatedAt"], item["appliedMarkers"]) == expected_evidence[item["issue"]], "Issue readback evidence가 다릅니다.")
    issue_116_evidence = next(item for item in evidence if item["issue"] == 116)
    _require(issue_116_evidence["blankLineNormalizedBodySha256"] == "de24ed51cd99f944a6a0ed10eba089252e906f8fbb25e2ff0789bc5ea6ebd5da", "Issue #116 normalized body SHA가 다릅니다.")
    _require(canonical_digest(contract) == CANONICAL_CONTRACT_SHA256, "계약 canonical tree가 변경됐습니다.")

    _require(isinstance(fixture, dict) and set(fixture) == {"contractVersion", "scheduleCases", "creationDecisionCases", "ttlCases", "cancellationCases", "platformCases", "collapseKeyCases", "consentCases", "aggregationCases", "retryExhaustionCases", "safetyBufferChangeCases", "leaseReclaimCases", "targetRaceCases", "completionCases", "targetTransitionCases", "retryInactiveSkipCases", "messageCases"}, "fixture root는 canonical closed object여야 합니다.")
    _require(canonical_digest(fixture) == CANONICAL_FIXTURE_SHA256, "fixture canonical tree가 변경됐습니다.")
    _require(fixture["contractVersion"] == contract["contractVersion"], "fixture contractVersion이 다릅니다.")
    _require(len(fixture["scheduleCases"]) == 14, "schedule fixture case 수가 다릅니다.")
    for case in fixture["scheduleCases"]:
        _require(evaluate_schedule_case(case, contract) == case["expected"], f"schedule fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["creationDecisionCases"]) == 5, "creation decision fixture case 수가 다릅니다.")
    for case in fixture["creationDecisionCases"]:
        _require(evaluate_creation_decision(case, contract) == case["expected"], f"creation decision fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["ttlCases"]) == 5, "TTL fixture case 수가 다릅니다.")
    for case in fixture["ttlCases"]:
        _require(isinstance(case, dict) and set(case) == {"id", "sendAttemptAt", "expiresAt", "expectedTtlSeconds"}, "TTL fixture case는 closed object여야 합니다.")
        _require(calculate_ttl_seconds(case["sendAttemptAt"], case["expiresAt"], 900) == case["expectedTtlSeconds"], f"TTL fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["cancellationCases"]) == 10, "취소 fixture case 수가 다릅니다.")
    for case in fixture["cancellationCases"]:
        _require(isinstance(case, dict) and set(case) == {"id", "trigger", "expectedReason"}, "취소 fixture case는 closed object여야 합니다.")
        _require(resolve_cancel_reason(case["trigger"], contract) == case["expectedReason"], f"취소 fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["platformCases"]) == 2, "platform fixture case 수가 다릅니다.")
    for case in fixture["platformCases"]:
        _require(map_platform_config(case, contract) == case["expected"], f"platform fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["collapseKeyCases"]) == 4, "collapse key fixture case 수가 다릅니다.")
    for case in fixture["collapseKeyCases"]:
        _require(isinstance(case, dict) and set(case) == {"id", "value", "expectedValid"} and type(case["expectedValid"]) is bool, "collapse key fixture는 closed object여야 합니다.")
        try:
            validate_collapse_key(case["value"])
            valid = True
        except ValueError:
            valid = False
        _require(valid is case["expectedValid"], f"collapse key fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["consentCases"]) == 11, "consent fixture case 수가 다릅니다.")
    for case in fixture["consentCases"]:
        _require(evaluate_consent_case(case) == case["expected"], f"consent fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["aggregationCases"]) == 8, "aggregation fixture case 수가 다릅니다.")
    for case in fixture["aggregationCases"]:
        _require(aggregate_target_states(case, contract) == case["expected"], f"aggregation fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["retryExhaustionCases"]) == 3, "retry exhaustion fixture case 수가 다릅니다.")
    for case in fixture["retryExhaustionCases"]:
        _require(complete_retryable_attempt(case, contract) == case["expected"], f"retry exhaustion fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["safetyBufferChangeCases"]) == 3, "buffer change fixture case 수가 다릅니다.")
    for case in fixture["safetyBufferChangeCases"]:
        _require(evaluate_buffer_change_case(case, contract) == case["expected"], f"buffer change fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["leaseReclaimCases"]) == 3, "lease reclaim fixture case 수가 다릅니다.")
    for case in fixture["leaseReclaimCases"]:
        _require(evaluate_lease_reclaim_case(case, contract) == case["expected"], f"lease reclaim fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["targetRaceCases"]) == 6, "target race fixture case 수가 다릅니다.")
    for case in fixture["targetRaceCases"]:
        _require(evaluate_target_race_case(case, contract) == case["expected"], f"target race fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["completionCases"]) == 7, "completion fixture case 수가 다릅니다.")
    for case in fixture["completionCases"]:
        _require(evaluate_completion_case(case, contract) == case["expected"], f"completion fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["targetTransitionCases"]) == 13, "target transition fixture case 수가 다릅니다.")
    for case in fixture["targetTransitionCases"]:
        _require(evaluate_target_transition_case(case, contract) == case["expected"], f"target transition fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["retryInactiveSkipCases"]) == 3, "retry inactive skip fixture case 수가 다릅니다.")
    for case in fixture["retryInactiveSkipCases"]:
        _require(evaluate_retry_inactive_skip_case(case, contract) == case["expected"], f"retry inactive skip fixture 결과가 다릅니다: {case.get('id')}")
    _require(len(fixture["messageCases"]) == 3, "message fixture case 수가 다릅니다.")
    for case in fixture["messageCases"]:
        _require(build_notification(case, contract) == case["expectedNotification"], f"message fixture 결과가 다릅니다: {case.get('id')}")
    assert_no_sensitive_values(fixture)


def _load_json(path: Path) -> Any:
    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            parse_constant=_reject_json_constant,
            object_pairs_hook=_reject_duplicate_pairs,
        )
    except DuplicateJsonKeyError as exc:
        raise ValueError(str(exc)) from exc
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise ValueError(f"JSON을 읽을 수 없습니다: {path}: {exc}") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Issue #112 FCM 출발 알림 계약 검증")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    args = parser.parse_args(argv)
    try:
        validate_contract(_load_json(args.contract), _load_json(args.fixture))
    except ValueError as exc:
        print(f"FCM 출발 알림 계약 검증 실패: {exc}", file=sys.stderr)
        return 1
    print(f"FCM 출발 알림 계약 검증 성공: {args.contract}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
