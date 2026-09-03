from __future__ import annotations

import math
from collections import Counter
from datetime import datetime
from typing import Any, Callable, Mapping, Sequence


MODES = ("PEDESTRIAN", "DRIVING", "PUBLIC_TRANSIT")
FIELD_NAMES = ("duration", "distance", "fare", "walkSegment", "polyline")
JEJU_BOUNDS = {
    "minimumLatitude": 33.0,
    "maximumLatitude": 34.0,
    "minimumLongitude": 126.0,
    "maximumLongitude": 127.0,
}


class ContractViolation(ValueError):
    """TMAP PoC의 공개되지 않는 실행 계약 위반입니다."""


class ProviderFailure(RuntimeError):
    """원문 없이 HTTP 상태만 보존하는 live transport 실패입니다."""

    def __init__(self, status_code: int | None) -> None:
        super().__init__("PROVIDER_FAILURE")
        self.status_code = status_code


def _require_endpoint(endpoint: Mapping[str, Any]) -> dict[str, Any]:
    latitude = endpoint.get("latitude")
    longitude = endpoint.get("longitude")
    if (
        isinstance(latitude, bool)
        or not isinstance(latitude, (int, float))
        or (isinstance(latitude, float) and not math.isfinite(latitude))
    ):
        raise ContractViolation("INVALID_LATITUDE")
    if (
        isinstance(longitude, bool)
        or not isinstance(longitude, (int, float))
        or (isinstance(longitude, float) and not math.isfinite(longitude))
    ):
        raise ContractViolation("INVALID_LONGITUDE")
    if not JEJU_BOUNDS["minimumLatitude"] <= latitude <= JEJU_BOUNDS["maximumLatitude"]:
        raise ContractViolation("OUTSIDE_JEJU_BOUNDS")
    if not JEJU_BOUNDS["minimumLongitude"] <= longitude <= JEJU_BOUNDS["maximumLongitude"]:
        raise ContractViolation("OUTSIDE_JEJU_BOUNDS")
    if endpoint.get("basis") != "PUBLIC_PLACE_REPRESENTATIVE_POINT":
        raise ContractViolation("UNAPPROVED_ENDPOINT_BASIS")
    return {
        "latitude": latitude,
        "longitude": longitude,
        "basis": endpoint["basis"],
    }


def build_requests(manifest: Mapping[str, Any]) -> list[dict[str, Any]]:
    """고정 manifest에서 case·mode·departure가 유일한 30개 요청을 만듭니다."""
    contract = manifest.get("requestContract", {})
    departure_at = contract.get("departureAt")
    try:
        parsed_departure = datetime.fromisoformat(departure_at)
    except (TypeError, ValueError) as error:
        raise ContractViolation("INVALID_DEPARTURE_AT") from error
    if parsed_departure.utcoffset() is None or parsed_departure.utcoffset().total_seconds() != 9 * 3600:
        raise ContractViolation("DEPARTURE_MUST_USE_ASIA_SEOUL_OFFSET")
    try:
        earliest_departure = datetime.fromisoformat(contract.get("earliestDepartureAt"))
        latest_departure = datetime.fromisoformat(contract.get("latestDepartureAt"))
    except (TypeError, ValueError) as error:
        raise ContractViolation("INVALID_DEPARTURE_WINDOW") from error
    if not earliest_departure <= parsed_departure <= latest_departure:
        raise ContractViolation("DEPARTURE_OUTSIDE_APPROVED_WINDOW")
    if contract.get("crs") != "EPSG:4326":
        raise ContractViolation("UNSUPPORTED_CRS")
    if contract.get("coordinateOrder") != "LONGITUDE_LATITUDE":
        raise ContractViolation("INVALID_COORDINATE_ORDER")

    policy = manifest.get("providerPolicy", {})
    required_provider_policy = {
        "PEDESTRIAN": (
            "tmap.pedestrian",
            "apis.openapi.sk.com",
            "/tmap/routes/pedestrian",
        ),
        "DRIVING": (
            "tmap.driving",
            "apis.openapi.sk.com",
            "/tmap/routes",
        ),
    }
    for mode, (source_id, host, path) in required_provider_policy.items():
        mode_policy = policy.get(mode, {})
        if (
            mode_policy.get("sourceId") != source_id
            or mode_policy.get("allowedHost") != host
            or mode_policy.get("allowedPathPrefix") != path
            or mode_policy.get("tmapCallAllowed") is not True
        ):
            raise ContractViolation("UNAPPROVED_TMAP_SOURCE_CONTRACT")
    transit_policy = policy.get("PUBLIC_TRANSIT", {})
    if (
        transit_policy.get("providerBoundary") != "OFFICIAL_TIMETABLE_AND_TAGO"
        or transit_policy.get("tmapCallAllowed") is not False
    ):
        raise ContractViolation("TMAP_PUBLIC_TRANSIT_MUST_REMAIN_DISABLED")

    routes = manifest.get("goldenRoutes")
    if not isinstance(routes, list) or len(routes) != 10:
        raise ContractViolation("EXACTLY_TEN_ROUTES_REQUIRED")

    requests: list[dict[str, Any]] = []
    route_ids: set[str] = set()
    for route in routes:
        route_id = route.get("id")
        if not isinstance(route_id, str) or not route_id or route_id in route_ids:
            raise ContractViolation("UNIQUE_ROUTE_ID_REQUIRED")
        route_ids.add(route_id)
        if tuple(route.get("modes", ())) != MODES:
            raise ContractViolation("EXACT_THREE_MODES_REQUIRED")
        origin = _require_endpoint(route.get("origin", {}))
        destination = _require_endpoint(route.get("destination", {}))
        for mode in MODES:
            requests.append(
                {
                    "caseId": route_id,
                    "mode": mode,
                    "departureAt": departure_at,
                    "crs": contract["crs"],
                    "coordinateOrder": contract["coordinateOrder"],
                    "origin": origin,
                    "destination": destination,
                }
            )
    return requests


def classify_failure(status_code: int | None, error: BaseException | None) -> str:
    """공급자 본문이나 예외 메시지를 노출하지 않고 실패를 안정 code로 바꿉니다."""
    if isinstance(error, TimeoutError):
        return "TIMEOUT"
    if status_code == 429:
        return "QUOTA_EXCEEDED"
    if status_code is not None and 500 <= status_code <= 599:
        return "PROVIDER_UNAVAILABLE"
    return "MALFORMED_RESPONSE"


def sanitize_response(
    *,
    case_id: str,
    mode: str,
    departure_at: str,
    response: Any,
) -> dict[str, Any]:
    """원문 수치를 복사하지 않고 필드 가용성만 남깁니다."""
    if mode not in MODES:
        raise ContractViolation("UNSUPPORTED_MODE")
    if not isinstance(response, Mapping):
        raise ContractViolation("MALFORMED_RESPONSE")

    def numeric_field_available(key: str) -> bool:
        value = response.get(key)
        if value is None:
            return False
        if (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or (isinstance(value, float) and not math.isfinite(value))
            or value < 0
        ):
            raise ContractViolation("MALFORMED_RESPONSE")
        return True

    walk_segments = response.get("walkSegments")
    if walk_segments is not None and not isinstance(walk_segments, list):
        raise ContractViolation("MALFORMED_RESPONSE")
    geometry = response.get("geometry")
    if geometry is not None and not isinstance(geometry, (str, list, dict)):
        raise ContractViolation("MALFORMED_RESPONSE")

    availability = {
        "duration": numeric_field_available("durationSeconds"),
        "distance": numeric_field_available("distanceMeters"),
        "fare": numeric_field_available("fareKrw"),
        "walkSegment": bool(walk_segments),
        "polyline": bool(geometry),
    }
    return {
        "caseId": case_id,
        "mode": mode,
        "departureAt": departure_at,
        "status": "SUCCESS",
        "reasonCode": "OK",
        "fieldAvailability": availability,
    }


def sanitized_failure(
    *, case_id: str, mode: str, departure_at: str, reason_code: str
) -> dict[str, Any]:
    """실패 observation에도 원문이나 개별 경로 수치를 포함하지 않습니다."""
    return {
        "caseId": case_id,
        "mode": mode,
        "departureAt": departure_at,
        "status": "FAILED",
        "reasonCode": reason_code,
        "fieldAvailability": {field: False for field in FIELD_NAMES},
    }


def aggregate_observations(
    requests: Sequence[Mapping[str, Any]],
    observations: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """정확한 request 결합과 일치하는 observation에서만 집계를 만듭니다."""
    expected = {
        (request["caseId"], request["mode"], request["departureAt"])
        for request in requests
    }
    actual = [
        (observation.get("caseId"), observation.get("mode"), observation.get("departureAt"))
        for observation in observations
    ]
    if len(expected) != 30 or len(actual) != 30 or set(actual) != expected:
        raise ContractViolation("INCOMPLETE_OR_DUPLICATE_OBSERVATIONS")
    if len(set(actual)) != len(actual):
        raise ContractViolation("INCOMPLETE_OR_DUPLICATE_OBSERVATIONS")

    status_counts = Counter(str(observation.get("status")) for observation in observations)
    reason_counts = Counter(str(observation.get("reasonCode")) for observation in observations)
    field_counts = {
        field: sum(
            bool(observation.get("fieldAvailability", {}).get(field))
            for observation in observations
        )
        for field in FIELD_NAMES
    }
    mode_counts = {
        mode: Counter(
            str(observation.get("status"))
            for observation in observations
            if observation.get("mode") == mode
        )
        for mode in MODES
    }
    field_counts_by_mode = {
        mode: {
            field: {
                "available": available,
                "missing": 10 - available,
                "availabilityRate": available / 10,
                "missingRate": (10 - available) / 10,
            }
            for field in FIELD_NAMES
            for available in (
                sum(
                    bool(observation.get("fieldAvailability", {}).get(field))
                    for observation in observations
                    if observation.get("mode") == mode
                ),
            )
        }
        for mode in MODES
    }
    return {
        "total": len(observations),
        "statusCounts": dict(sorted(status_counts.items())),
        "reasonCodeCounts": dict(sorted(reason_counts.items())),
        "fieldAvailability": field_counts,
        "fieldAvailabilityByMode": field_counts_by_mode,
        "modeStatusCounts": {
            mode: dict(sorted(counts.items())) for mode, counts in mode_counts.items()
        },
    }


def live_preflight(
    manifest: Mapping[str, Any], environment: Mapping[str, str]
) -> dict[str, Any]:
    """키와 승인 source 경계를 통과하기 전에는 live 호출을 계획하지 않습니다."""
    build_requests(manifest)
    if not environment.get("JEJU_TMAP_API_KEY"):
        return {
            "status": "SKIPPED",
            "reasonCode": "APPROVED_TMAP_KEY_NOT_PRESENT",
            "plannedNetworkCalls": 0,
        }
    policy = manifest.get("providerPolicy", {})
    if policy.get("PUBLIC_TRANSIT", {}).get("tmapCallAllowed") is not False:
        raise ContractViolation("TMAP_PUBLIC_TRANSIT_MUST_REMAIN_DISABLED")
    return {
        "status": "READY",
        "reasonCode": "APPROVED_SOURCES_ONLY",
        "plannedNetworkCalls": 20,
        "nonTmapCases": 10,
    }


def execute_live_matrix(
    manifest: Mapping[str, Any],
    environment: Mapping[str, str],
    *,
    tmap_transport: Callable[[Mapping[str, Any], str], Mapping[str, Any]],
    official_transit_transport: Callable[[Mapping[str, Any]], Mapping[str, Any]],
) -> dict[str, Any]:
    """승인 transport를 주입받아 30-case를 실행하고 sanitized 집계만 반환합니다."""
    preflight = live_preflight(manifest, environment)
    if preflight["status"] != "READY":
        return preflight

    requests = build_requests(manifest)
    observations: list[dict[str, Any]] = []
    api_key = environment["JEJU_TMAP_API_KEY"]
    for request in requests:
        try:
            if request["mode"] == "PUBLIC_TRANSIT":
                response = official_transit_transport(request)
            else:
                response = tmap_transport(request, api_key)
            observation = sanitize_response(
                case_id=request["caseId"],
                mode=request["mode"],
                departure_at=request["departureAt"],
                response=response,
            )
        except ProviderFailure as error:
            observation = sanitized_failure(
                case_id=request["caseId"],
                mode=request["mode"],
                departure_at=request["departureAt"],
                reason_code=classify_failure(error.status_code, error),
            )
        except TimeoutError as error:
            observation = sanitized_failure(
                case_id=request["caseId"],
                mode=request["mode"],
                departure_at=request["departureAt"],
                reason_code=classify_failure(None, error),
            )
        except ContractViolation:
            observation = sanitized_failure(
                case_id=request["caseId"],
                mode=request["mode"],
                departure_at=request["departureAt"],
                reason_code="MALFORMED_RESPONSE",
            )
        except Exception:
            observation = sanitized_failure(
                case_id=request["caseId"],
                mode=request["mode"],
                departure_at=request["departureAt"],
                reason_code="TRANSPORT_FAILURE",
            )
        observations.append(observation)

    return {
        "status": "COMPLETED",
        "reasonCode": "SANITIZED_AGGREGATE_ONLY",
        "aggregate": aggregate_observations(requests, observations),
    }
