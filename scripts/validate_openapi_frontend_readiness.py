#!/usr/bin/env python3
"""Fail-closed, dependency-free frontend-readiness checks for generated OpenAPI JSON."""

import argparse
import json
import math
import re
import subprocess
import sys
from datetime import date, datetime
from pathlib import Path
from urllib.parse import urlparse


HTTP_METHODS = {"get", "put", "post", "delete", "patch", "options", "head", "trace"}
AUTO_OPERATION_ID = re.compile(r"(?:_\d+|^(?:get|list|read|create|update|patch|delete)$)")
STABLE_OPERATION_ID = re.compile(r"^[a-z][A-Za-z0-9]*(?:List|Read|Create|Update|Delete)$")
SECRET_LIKE = re.compile(
    r"(?:sk_(?:live|test)_[A-Za-z0-9]{12,}|gh[pousr]_[A-Za-z0-9]{20,}|"
    r"AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----)",
    re.IGNORECASE,
)
UUID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
DATE_TIME = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$")
REQUIRED_REQUEST_HEADERS = {
    ("POST", "/api/v1/me/saved-places"): {"Idempotency-Key"},
    ("PATCH", "/api/v1/me/saved-places/{placeId}"): {"If-Match"},
    ("POST", "/api/v1/trips"): {"Idempotency-Key"},
    ("PATCH", "/api/v1/trips/{tripId}"): {"If-Match"},
    ("POST", "/api/v1/trips/{tripId}/schedule-items"): {
        "Idempotency-Key",
        "If-Match",
    },
    ("POST", "/api/v1/trips/{tripId}/accommodations"): {
        "Idempotency-Key",
        "If-Match",
    },
    ("PATCH", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"): {
        "If-Match"
    },
    ("DELETE", "/api/v1/trips/{tripId}/accommodations/{accommodationId}"): {
        "If-Match"
    },
    ("PUT", "/api/v1/trips/{tripId}/transport-event"): {"If-Match"},
    ("DELETE", "/api/v1/trips/{tripId}/transport-event"): {"If-Match"},
}
REQUIRED_RESPONSE_HEADERS = {
    ("POST", "/api/v1/me/saved-places", "200"): {"Location", "ETag", "Idempotency-Replayed"},
    ("POST", "/api/v1/me/saved-places", "201"): {"Location", "ETag", "Idempotency-Replayed"},
    ("PATCH", "/api/v1/me/saved-places/{placeId}", "200"): {"ETag"},
    ("POST", "/api/v1/trips", "201"): {"Location", "ETag", "Idempotency-Replayed"},
    ("GET", "/api/v1/trips/{tripId}", "200"): {"ETag"},
    ("PATCH", "/api/v1/trips/{tripId}", "200"): {"ETag"},
    ("PUT", "/api/v1/trips/{tripId}/preferences", "200"): {"ETag"},
    ("POST", "/api/v1/trips/{tripId}/schedule-items", "201"): {
        "ETag",
        "Idempotency-Replayed",
    },
    ("POST", "/api/v1/trips/{tripId}/schedule-items", "409"): {"Retry-After"},
    ("POST", "/api/v1/trips/{tripId}/accommodations", "201"): {
        "Location",
        "ETag",
        "Idempotency-Replayed",
    },
    (
        "PATCH",
        "/api/v1/trips/{tripId}/accommodations/{accommodationId}",
        "200",
    ): {"ETag"},
    ("PUT", "/api/v1/trips/{tripId}/transport-event", "200"): {"ETag"},
    ("DELETE", "/api/v1/trips/{tripId}/transport-event", "200"): {"ETag"},
}
CURRENT_OPERATIONS = {
    ("GET", "/api/v1/auth/social/providers"): "authSocialProvidersList",
    ("GET", "/api/v1/auth/social/naver/userinfo"): "authNaverUserInfoRead",
    ("GET", "/api/v1/me"): "profileRead",
    ("PATCH", "/api/v1/me"): "profileUpdate",
    ("GET", "/api/v1/legal-documents"): "legalDocumentsList",
    ("PUT", "/api/v1/me/consents"): "legalConsentsUpdate",
    ("GET", "/api/v1/places"): "placesList",
    ("GET", "/api/v1/places/{placeId}"): "placesRead",
    ("GET", "/api/v1/weather/forecast"): "weatherForecastRead",
}
SAVED_PLACE_OPERATIONS = {
    ("GET", "/api/v1/me/saved-places"): "savedPlacesList",
    ("POST", "/api/v1/me/saved-places"): "savedPlacesCreate",
    ("PATCH", "/api/v1/me/saved-places/{placeId}"): "savedPlacesUpdate",
    ("DELETE", "/api/v1/me/saved-places/{placeId}"): "savedPlacesDelete",
}
TRIP_OPERATIONS = {
    ("GET", "/api/v1/trips"): "tripsList",
    ("POST", "/api/v1/trips"): "tripsCreate",
    ("GET", "/api/v1/trips/{tripId}"): "tripsRead",
}
TRIP_MUTATION_OPERATIONS = {
    ("PATCH", "/api/v1/trips/{tripId}"): "tripsUpdate",
    ("DELETE", "/api/v1/trips/{tripId}"): "tripsDelete",
}
ACCOMMODATION_OPERATIONS = {
    (
        "POST",
        "/api/v1/trips/{tripId}/accommodations",
    ): "tripAccommodationsCreate",
    (
        "PATCH",
        "/api/v1/trips/{tripId}/accommodations/{accommodationId}",
    ): "tripAccommodationsUpdate",
    (
        "DELETE",
        "/api/v1/trips/{tripId}/accommodations/{accommodationId}",
    ): "tripAccommodationsDelete",
}
PUSH_NOTIFICATION_OPERATIONS = {
    ("PUT", "/api/v1/me/push-devices/{deviceId}"): "pushDevicesUpdate",
    ("DELETE", "/api/v1/me/push-devices/{deviceId}"): "pushDevicesDelete",
    ("GET", "/api/v1/me/notification-preferences"): "notificationPreferencesRead",
    ("PATCH", "/api/v1/me/notification-preferences"): "notificationPreferencesUpdate",
}
SCHEDULE_OPERATIONS = {
    ("GET", "/api/v1/trips/{tripId}/schedule"): "tripScheduleRead",
}
SCHEDULE_MUTATION_OPERATIONS = {
    ("POST", "/api/v1/trips/{tripId}/schedule-items"): "tripScheduleItemCreate",
}
TRANSPORT_EVENT_OPERATIONS = {
    ("PUT", "/api/v1/trips/{tripId}/transport-event"): "tripTransportEventsUpdate",
    ("DELETE", "/api/v1/trips/{tripId}/transport-event"): "tripTransportEventsDelete",
}
PREFERENCES_OPERATIONS = {
    ("PUT", "/api/v1/trips/{tripId}/preferences"): "tripPreferencesUpdate",
}
EXPECTED_OPERATION_IDS = (
    CURRENT_OPERATIONS
    | SAVED_PLACE_OPERATIONS
    | TRIP_OPERATIONS
    | TRIP_MUTATION_OPERATIONS
    | PUSH_NOTIFICATION_OPERATIONS
    | SCHEDULE_OPERATIONS
    | SCHEDULE_MUTATION_OPERATIONS
    | ACCOMMODATION_OPERATIONS
    | TRANSPORT_EVENT_OPERATIONS
    | PREFERENCES_OPERATIONS
)
PUBLIC_OPERATIONS = {
    ("GET", "/api/v1/auth/social/providers"),
    ("GET", "/api/v1/auth/social/naver/userinfo"),
}
OPTIONAL_SECURITY_OPERATIONS = {
    ("GET", "/api/v1/legal-documents"),
    ("GET", "/api/v1/places"),
    ("GET", "/api/v1/places/{placeId}"),
    ("GET", "/api/v1/weather/forecast"),
}
SOURCE_PROVENANCE_16 = {
    "saved-places": "bd83872b1fd91d5e5c1980422634198734c92cf1",
    "trips": "9a4c4b2f78d61d8f37e8f27646f888eddd28a2de",
}
SOURCE_PROVENANCE_20 = {
    **SOURCE_PROVENANCE_16,
    "push-notifications": "26ec730ae4d8d9b64356e624a4bf5021dbdc4d76",
}
SOURCE_PROVENANCE_21 = {
    **SOURCE_PROVENANCE_20,
    "schedules": "a5f53adcf43a63672de76d2a0ec4579257cb664a",
}
SOURCE_PROVENANCE_23 = dict(SOURCE_PROVENANCE_21)
ACCOMMODATION_SOURCE = "0335c49e5e60c11e5a365c67dbee970a11d247c5"
SOURCE_PROVENANCE_27 = {**SOURCE_PROVENANCE_23, "accommodations": ACCOMMODATION_SOURCE}
SOURCE_PROVENANCE_25 = {
    **SOURCE_PROVENANCE_21,
    "preferences-transport": "c6862499d71519d9efc7bfcf72855703d1e94f0a",
}
SOURCE_PROVENANCE_29 = {
    **SOURCE_PROVENANCE_27,
    "preferences-transport": "5914e3c82673f8f49f36c1a9944308e096e98ade",
}
SOURCE_PROVENANCE_30 = {
    **SOURCE_PROVENANCE_27,
    "preferences": "c6862499d71519d9efc7bfcf72855703d1e94f0a",
    "transport-events": "5914e3c82673f8f49f36c1a9944308e096e98ade",
}


def operations_for_mode(mode):
    required = dict(CURRENT_OPERATIONS)
    if mode in (16, 20, 21, 23, 24, 25, 27, 29, 30):
        required.update(SAVED_PLACE_OPERATIONS)
        required.update(TRIP_OPERATIONS)
    if mode in (20, 21, 23, 24, 25, 27, 29, 30):
        required.update(PUSH_NOTIFICATION_OPERATIONS)
    if mode in (21, 23, 24, 25, 27, 29, 30):
        required.update(SCHEDULE_OPERATIONS)
    if mode in (23, 24, 25, 27, 29, 30):
        required.update(TRIP_MUTATION_OPERATIONS)
    if mode in (24, 25, 27, 29, 30):
        required.update(SCHEDULE_MUTATION_OPERATIONS)
    if mode in (27, 29, 30):
        required.update(ACCOMMODATION_OPERATIONS)
    if mode in (29, 30):
        required.update(TRANSPORT_EVENT_OPERATIONS)
    if mode in (25, 30):
        required.update(PREFERENCES_OPERATIONS)
    return required


def source_provenance_for_mode(mode):
    if mode == 30:
        return dict(SOURCE_PROVENANCE_30)
    if mode == 29:
        return dict(SOURCE_PROVENANCE_29)
    if mode == 27:
        return dict(SOURCE_PROVENANCE_27)
    if mode == 25:
        return dict(SOURCE_PROVENANCE_25)
    if mode in (21, 23, 24):
        return dict(SOURCE_PROVENANCE_21)
    if mode == 20:
        return dict(SOURCE_PROVENANCE_20)
    return dict(SOURCE_PROVENANCE_16)
SCHEMA_CONSTRAINT_KEYS = {
    "type",
    "minLength",
    "maxLength",
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "minItems",
    "maxItems",
    "uniqueItems",
    "enum",
    "default",
    "format",
    "pattern",
    "minProperties",
    "additionalProperties",
}


class Validator:
    def __init__(self, document, mode, contracts_root):
        self.document = document
        self.mode = mode
        self.contracts_root = contracts_root
        self.errors = []
        self.operation_ids = {}
        self.operations = set()
        self.source_provenance = source_provenance_for_mode(mode)
        self.runtime_manifest = None
        self.runtime_problem_definitions = {}

    def error(self, location, message):
        self.errors.append(f"{location}: {message}")

    def resolve(self, value, location):
        seen = set()
        while isinstance(value, dict) and "$ref" in value:
            siblings = {key: child for key, child in value.items() if key != "$ref"}
            ref = value["$ref"]
            if not isinstance(ref, str) or not ref.startswith("#/"):
                self.error(location, f"외부 또는 잘못된 $ref는 허용하지 않습니다: {ref!r}")
                return {}
            if ref in seen:
                self.error(location, f"순환 $ref입니다: {ref}")
                return {}
            seen.add(ref)
            target = self.document
            try:
                for part in ref[2:].split("/"):
                    target = target[part.replace("~1", "/").replace("~0", "~")]
            except (KeyError, TypeError):
                self.error(location, f"해결할 수 없는 $ref입니다: {ref}")
                return {}
            value = {**target, **siblings} if isinstance(target, dict) else target
        return value

    @staticmethod
    def examples(media):
        values = []
        if "example" in media:
            values.append(media["example"])
        for entry in (media.get("examples") or {}).values():
            if isinstance(entry, dict) and "value" in entry:
                values.append(entry["value"])
        return values

    def validate(self, include_authority=True):
        if not isinstance(self.document, dict):
            self.error("$", "OpenAPI JSON root는 object여야 합니다")
            return self.errors
        paths = self.document.get("paths")
        if not isinstance(paths, dict) or not paths:
            self.error("paths", "공개 API path가 없습니다")
            return self.errors
        for path, path_item in paths.items():
            if not path.startswith("/api/v1/"):
                self.error(path, "internal endpoint가 OpenAPI에 노출되었습니다")
                continue
            if not isinstance(path_item, dict):
                self.error(path, "PathItem은 object여야 합니다")
                continue
            for method, operation in path_item.items():
                if method.lower() not in HTTP_METHODS:
                    continue
                self.validate_operation(path, method.lower(), operation)
        for operation_id, locations in self.operation_ids.items():
            if len(locations) > 1:
                self.error(", ".join(locations), f"operationId {operation_id!r}가 중복입니다")
        self.validate_operation_inventory()
        self.validate_component_schemas()
        self.validate_security()
        self.scan_examples(self.document, "$")
        self.validate_known_headers()
        if include_authority:
            self.validate_contract_authority()
        if self.mode in (16, 20, 21, 23, 24, 25, 27, 29, 30):
            self.validate_source_provenance()
        return self.errors

    def validate_source_provenance(self):
        for domain, clean_head in self.source_provenance.items():
            try:
                result = subprocess.run(
                    [
                        "git",
                        "-C",
                        str(self.contracts_root),
                        "merge-base",
                        "--is-ancestor",
                        clean_head,
                        "HEAD",
                    ],
                    capture_output=True,
                    text=True,
                    check=False,
                )
            except OSError as error:
                self.error(
                    f"source provenance {domain}",
                    f"Git ancestry를 검사할 수 없습니다: {error}",
                )
                continue
            if result.returncode != 0:
                self.error(
                    f"source provenance {domain}",
                    f"{self.mode}-operation artifact는 clean HEAD {clean_head}를 포함한 checkout에서 생성되어야 합니다",
                )

    def validate_contract_authority(self):
        catalog = self.read_authority_json("docs/contracts/rest/catalog.json")
        manifest = self.read_authority_json("scripts/openapi_frontend_runtime_manifest.json")
        if catalog is None or manifest is None:
            return
        self.runtime_manifest = manifest.get("operations")
        self.runtime_problem_definitions = manifest.get("runtimeProblemDefinitions") or {}
        if not isinstance(self.runtime_manifest, dict):
            self.error("scripts/openapi_frontend_runtime_manifest.json", "operations map이 없습니다")
            return
        catalog_endpoints = {
            (entry.get("method"), entry.get("path")): entry
            for entry in catalog.get("endpoints", [])
            if isinstance(entry, dict)
        }
        groups = [
            ("profile-legal", {key: value for key, value in CURRENT_OPERATIONS.items() if key[1] in {"/api/v1/me", "/api/v1/legal-documents", "/api/v1/me/consents"}}),
            ("places", {key: value for key, value in CURRENT_OPERATIONS.items() if key[1].startswith("/api/v1/places")}),
            ("weather-forecast", {key: value for key, value in CURRENT_OPERATIONS.items() if key[1] == "/api/v1/weather/forecast"}),
        ]
        if self.mode in (16, 20, 21, 23, 24, 25, 27, 29, 30):
            trip_operations = dict(TRIP_OPERATIONS)
            if self.mode in (23, 24, 25, 27, 29, 30):
                trip_operations.update(TRIP_MUTATION_OPERATIONS)
            groups.extend((("saved-places", SAVED_PLACE_OPERATIONS), ("trips", trip_operations)))
        if self.mode in (20, 21, 23, 24, 25, 27, 29, 30):
            groups.append(("push-notifications", PUSH_NOTIFICATION_OPERATIONS))
        if self.mode in (21, 23, 24, 25, 27, 29, 30):
            schedule_operations = dict(SCHEDULE_OPERATIONS)
            if self.mode in (24, 25, 27, 29, 30):
                schedule_operations.update(SCHEDULE_MUTATION_OPERATIONS)
            groups.append(("schedules", schedule_operations))
        if self.mode in (27, 29, 30):
            groups.append(("accommodations", ACCOMMODATION_OPERATIONS))
        if self.mode == 29:
            groups.append(("preferences-transport", TRANSPORT_EVENT_OPERATIONS))
        if self.mode == 25:
            groups.append(("preferences-transport", PREFERENCES_OPERATIONS))
        if self.mode == 30:
            groups.append(
                (
                    "preferences-transport",
                    PREFERENCES_OPERATIONS | TRANSPORT_EVENT_OPERATIONS,
                )
            )
        for domain, operation_group in groups:
            contract = self.read_authority_json(
                f"docs/contracts/domains/{domain}/contract.json"
            )
            if contract is None:
                continue
            schemas = contract.get("schemas") or {}
            endpoint_field = "endpoints"
            if domain == "push-notifications":
                schemas = self.push_notification_schemas(contract)
                endpoint_field = "endpointContracts"
            domain_endpoints = {
                (entry.get("method"), entry.get("path")): (
                    self.push_notification_endpoint(entry)
                    if domain == "push-notifications"
                    else entry
                )
                for entry in contract.get(endpoint_field, [])
                if isinstance(entry, dict)
            }
            for key in operation_group:
                self.validate_contract_endpoint(
                    key,
                    catalog_endpoints.get(key),
                    domain_endpoints.get(key),
                    schemas,
                    self.domain_problem_pairs(contract, domain_endpoints.get(key), key),
                )

    @classmethod
    def push_notification_schemas(cls, contract):
        limits = contract.get("limits") or {}

        def expand(value):
            if isinstance(value, dict):
                reference = value.get("$ref")
                if isinstance(reference, str) and reference.startswith("#/limits/"):
                    return expand(limits.get(reference.removeprefix("#/limits/"), {}))
                return {key: expand(child) for key, child in value.items()}
            if isinstance(value, list):
                return [expand(child) for child in value]
            return value

        schemas = {
            name: expand(schema) for name, schema in (contract.get("schemas") or {}).items()
        }
        for schema in schemas.values():
            for property_schema in (schema.get("properties") or {}).values():
                property_type = property_schema.get("type")
                if isinstance(property_type, list) and "null" in property_type:
                    property_schema["type"] = next(
                        (candidate for candidate in property_type if candidate != "null"),
                        None,
                    )
                    property_schema["nullable"] = True
        schemas["PushDevicePath"] = {
            "type": "object",
            "additionalProperties": False,
            "required": ["deviceId"],
            "properties": {"deviceId": expand(limits.get("deviceId") or {})},
        }
        return schemas

    @staticmethod
    def push_notification_endpoint(endpoint):
        success = endpoint.get("success") or {}
        return {
            **endpoint,
            "successSchema": success.get("schema", "none"),
            "successStatuses": [success.get("status")],
        }

    @staticmethod
    def domain_problem_pairs(contract, endpoint, key):
        pairs = set()
        for problem in (endpoint or {}).get("problems") or []:
            pairs.add((problem.get("code"), problem.get("type")))
        for codes in ((endpoint or {}).get("errorMatrix") or {}).values():
            for code in codes:
                pairs.add((code, None))
        operation_key = f"{key[0]} {key[1]}"
        matrix_codes = {
            code
            for codes in ((endpoint or {}).get("errorMatrix") or {}).values()
            for code in codes
        }
        for condition in contract.get("errorConditions") or []:
            condition_endpoints = condition.get("endpoints") or []
            if operation_key not in condition_endpoints and not (
                not condition_endpoints and condition.get("code") in matrix_codes
            ):
                continue
            pairs.add((condition.get("code"), condition.get("type")))
        return pairs

    def read_authority_json(self, relative_path):
        path = self.contracts_root / relative_path
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            self.error(relative_path, f"canonical authority를 읽을 수 없습니다: {error}")
            return None
        if not isinstance(value, dict):
            self.error(relative_path, "canonical authority root는 object여야 합니다")
            return None
        return value

    def validate_contract_endpoint(self, key, catalog, endpoint, schemas, domain_problem_pairs=None):
        location = f"{key[0]} {key[1]}"
        if not isinstance(catalog, dict) or not isinstance(endpoint, dict):
            self.error(location, "REST catalog/domain contract authority projection이 없습니다")
            return
        operation = (self.document.get("paths") or {}).get(key[1], {}).get(key[0].lower())
        if not isinstance(operation, dict):
            return
        responses = operation.get("responses") or {}
        canonical_responses = catalog.get("responses") or {}
        expected_statuses = {
            str(status)
            for status in canonical_responses.get("success", [])
            + canonical_responses.get("errors", [])
        }
        runtime = (self.runtime_manifest or {}).get(f"{key[0]} {key[1]}")
        if not isinstance(runtime, dict):
            self.error(location, "runtime-only manifest projection이 없습니다")
            return
        strict_problem_pairs = key == ("POST", "/api/v1/trips/{tripId}/schedule-items")
        for status, problem in (runtime.get("problems") or {}).items():
            if not isinstance(problem, list) or len(problem) != 2:
                self.error(location, f"runtime Problem {status} manifest 형식이 올바르지 않습니다")
                continue
            pair = tuple(problem)
            is_runtime_only = (
                self.runtime_problem_definitions.get(problem[0]) == problem[1]
                or str(status) in runtime.get("runtimeOnlyProblemStatuses", [])
            )
            pair_matches = (
                self.canonical_problem_pair_matches(pair, domain_problem_pairs)
                if strict_problem_pairs
                else pair in domain_problem_pairs or (problem[0], None) in domain_problem_pairs
            ) if domain_problem_pairs is not None else True
            runtime_only_allowed = is_runtime_only and (
                not strict_problem_pairs
                or domain_problem_pairs is None
                or not self.canonical_problem_code(problem[0], domain_problem_pairs)
            )
            if domain_problem_pairs is not None and not pair_matches and not runtime_only_allowed:
                self.error(location, f"Problem {status} code/type이 domain endpoint matrix와 다릅니다")
        for status, problem_set in (runtime.get("problemSets") or {}).items():
            if not isinstance(problem_set, list):
                self.error(location, f"runtime Problem 전체 집합 {status} manifest 형식이 올바르지 않습니다")
                continue
            for problem in problem_set:
                if not isinstance(problem, list) or len(problem) != 2:
                    self.error(location, f"runtime Problem 전체 집합 {status} 항목 형식이 올바르지 않습니다")
                    continue
                pair = tuple(problem)
                is_runtime_only = self.runtime_problem_definitions.get(problem[0]) == problem[1]
                pair_matches = (
                    self.canonical_problem_pair_matches(pair, domain_problem_pairs)
                    if strict_problem_pairs
                    else pair in domain_problem_pairs or (problem[0], None) in domain_problem_pairs
                ) if domain_problem_pairs is not None else True
                runtime_only_allowed = is_runtime_only and (
                    not strict_problem_pairs
                    or domain_problem_pairs is None
                    or not self.canonical_problem_code(problem[0], domain_problem_pairs)
                )
                if domain_problem_pairs is not None and not pair_matches and not runtime_only_allowed:
                    self.error(location, f"Problem 전체 집합 {status} code/type이 domain endpoint matrix와 다릅니다")
        expected_statuses.update(str(status) for status in runtime.get("statusAdditions", []))
        expected_statuses.difference_update(str(status) for status in runtime.get("statusOmissions", []))
        actual_statuses = {str(status) for status in responses if str(status).isdigit()}
        if actual_statuses != expected_statuses:
            self.error(
                location,
                f"canonical status projection이 다릅니다: expected={sorted(expected_statuses)}, actual={sorted(actual_statuses)}",
            )
        self.validate_contract_parameters(operation, catalog, schemas, location)
        self.validate_contract_body(operation, catalog, schemas, location)
        self.validate_contract_success(operation, endpoint, schemas, location)
        canonical_problem_examples = {}
        if key == ("POST", "/api/v1/trips/{tripId}/schedule-items"):
            fixture = self.read_authority_json("fixtures/contracts/schedules/problem.json") or {}
            canonical_problem_examples = {
                example.get("code"): example
                for example in (fixture.get("examples") or {}).values()
                if isinstance(example, dict) and isinstance(example.get("code"), str)
            }
            retry_after = ((responses.get("409") or {}).get("headers") or {}).get("Retry-After")
            if not isinstance(retry_after, dict) or "IDEMPOTENCY_KEY_REUSED" not in retry_after.get("description", ""):
                self.error(location, "409의 조건부 Retry-After 계약이 문서화되지 않았습니다")
        for status, raw_response in responses.items():
            if not str(status).isdigit() or int(status) < 400:
                continue
            response = self.resolve(raw_response, f"{location} response {status}")
            media = (response.get("content") or {}).get("application/problem+json") or {}
            canonical_codes = (endpoint.get("errorMatrix") or {}).get(str(status))
            if key in ACCOMMODATION_OPERATIONS and canonical_codes is not None:
                self.validate_named_problem_examples(
                    media,
                    canonical_codes,
                    domain_problem_pairs or set(),
                    int(status),
                    location,
                )
                continue
            actual_problem_pairs = set()
            for example in self.examples(media):
                if isinstance(example, dict):
                    actual_problem_pairs.add((example.get("code"), example.get("type")))
                    canonical_example = canonical_problem_examples.get(example.get("code"))
                    if canonical_example is not None:
                        exact_fields = ("type", "title", "status", "detail", "code", "fieldErrors")
                        if any(example.get(field) != canonical_example.get(field) for field in exact_fields):
                            self.error(
                                location,
                                f"response {status} Problem {example.get('code')}가 canonical fixture와 다릅니다",
                            )
            configured_set = (runtime.get("problemSets") or {}).get(str(status))
            if configured_set is not None:
                expected_problem_pairs = {
                    tuple(pair) for pair in configured_set if isinstance(pair, list) and len(pair) == 2
                }
                if actual_problem_pairs != expected_problem_pairs:
                    self.error(
                        location,
                        f"response {status} Problem 전체 집합이 다릅니다: expected={sorted(expected_problem_pairs)}, actual={sorted(actual_problem_pairs)}",
                    )
            else:
                expected_code = self.expected_problem_code(key, int(status))
                expected_type = self.expected_problem_type(expected_code)
                if actual_problem_pairs != {(expected_code, expected_type)}:
                    self.error(
                        location,
                        f"response {status} Problem code/type이 runtime representative와 다릅니다",
                    )

    @staticmethod
    def canonical_problem_code(code, pairs):
        return any(candidate == code for candidate, _ in pairs)

    @staticmethod
    def canonical_problem_pair_matches(pair, pairs):
        code, _ = pair
        typed = {candidate for candidate in pairs if candidate[0] == code and candidate[1] is not None}
        return pair in typed if typed else (code, None) in pairs

    def validate_named_problem_examples(
        self, media, expected_codes, domain_problem_pairs, status, location
    ):
        if "example" in media:
            self.error(location, f"response {status} 단일 Problem example은 허용하지 않습니다")
        examples = media.get("examples") or {}
        if set(examples) != set(expected_codes):
            self.error(location, f"response {status} named Problem examples가 canonical matrix와 다릅니다")
            return
        expected_types = {
            code: problem_type
            for code, problem_type in domain_problem_pairs
            if problem_type is not None
        }
        for code in expected_codes:
            value = (examples.get(code) or {}).get("value") or {}
            if value.get("code") != code or value.get("status") != status:
                self.error(location, f"response {status} named Problem {code} payload가 다릅니다")
            if value.get("type") != expected_types.get(code):
                self.error(location, f"response {status} named Problem {code} type이 다릅니다")

    def expected_problem_code(self, key, status):
        problem = (self.runtime_operations().get(f"{key[0]} {key[1]}") or {}).get("problems", {}).get(str(status))
        return problem[0] if isinstance(problem, list) and len(problem) == 2 else None

    def expected_problem_type(self, code, key=None, status=None):
        if key is not None and status is not None:
            problem = (
                (self.runtime_operations().get(f"{key[0]} {key[1]}") or {})
                .get("problems", {})
                .get(str(status))
            )
            if isinstance(problem, list) and len(problem) == 2 and problem[0] == code:
                return problem[1]
        for runtime in self.runtime_operations().values():
            for problem in (runtime.get("problems") or {}).values():
                if isinstance(problem, list) and len(problem) == 2 and problem[0] == code:
                    return problem[1]
        return None

    def runtime_operations(self):
        if self.runtime_manifest is None:
            manifest = self.read_authority_json("scripts/openapi_frontend_runtime_manifest.json")
            self.runtime_manifest = (manifest or {}).get("operations")
        return self.runtime_manifest if isinstance(self.runtime_manifest, dict) else {}

    def canonical_schema(self, raw_schema, schemas, location):
        schema = raw_schema
        seen = set()
        while isinstance(schema, dict) and "$ref" in schema:
            name = schema["$ref"]
            if name in seen or name not in schemas:
                self.error(location, f"canonical schema ref를 해결할 수 없습니다: {name}")
                return {}
            seen.add(name)
            siblings = {key: value for key, value in schema.items() if key != "$ref"}
            schema = {**schemas[name], **siblings}
        if (
            isinstance(schema, dict)
            and schema.get("unevaluatedProperties") is False
            and isinstance(schema.get("allOf"), list)
        ):
            properties = {}
            required = []
            for child in schema["allOf"]:
                branch = self.canonical_schema(child, schemas, location)
                if branch.get("type") not in (None, "object") or "allOf" in branch:
                    self.error(location, "canonical closed allOf branch가 object가 아닙니다")
                    return {}
                for name, value in (branch.get("properties") or {}).items():
                    if name in properties and properties[name] != value:
                        self.error(location, f"canonical closed allOf property가 충돌합니다: {name}")
                        return {}
                    properties[name] = value
                for name in branch.get("required") or []:
                    if name not in required:
                        required.append(name)
            if not set(required).issubset(properties):
                self.error(location, "canonical closed allOf required property가 없습니다")
                return {}
            flattened = {
                "type": "object",
                "additionalProperties": False,
                "required": required,
                "properties": properties,
            }
            if "nullable" in schema:
                flattened["nullable"] = schema["nullable"]
            return flattened
        return schema if isinstance(schema, dict) else {}

    def validate_contract_parameters(self, operation, catalog, schemas, location):
        parameters = {
            (parameter.get("in"), parameter.get("name")): parameter
            for raw_parameter in operation.get("parameters") or []
            for parameter in [self.resolve(raw_parameter, location)]
        }
        schema_names = catalog.get("schemas") or {}
        for parameter_kind, parameter_location in (("query", "query"), ("path", "path")):
            schema_name = schema_names.get(parameter_kind)
            if schema_name in (None, "none"):
                continue
            canonical = self.canonical_schema({"$ref": schema_name}, schemas, location)
            expected_names = set((canonical.get("properties") or {}))
            actual_names = {
                name for (where, name) in parameters if where == parameter_location
            }
            if actual_names != expected_names:
                self.error(location, f"canonical {parameter_kind} parameter projection이 다릅니다")
            for name, raw_property in (canonical.get("properties") or {}).items():
                parameter = parameters.get((parameter_location, name))
                if parameter is not None:
                    self.compare_schema(
                        raw_property,
                        parameter.get("schema") or {},
                        schemas,
                        f"{location} parameter {name}",
                    )
                    expected_required = name in set(canonical.get("required") or [])
                    if (parameter.get("required") is True) != expected_required:
                        self.error(location, f"parameter {name} required가 canonical과 다릅니다")
        header_name = schema_names.get("headers")
        if header_name not in (None, "none", "CommonHeaders"):
            canonical = self.canonical_schema({"$ref": header_name}, schemas, location)
            expected_header_names = set((canonical.get("properties") or {})) - {"Authorization"}
            actual_header_names = {
                name
                for (where, name) in parameters
                if where == "header" and name != "Authorization"
            }
            if actual_header_names != expected_header_names:
                self.error(location, "canonical request header projection이 다릅니다")
            for name, raw_property in (canonical.get("properties") or {}).items():
                if name == "Authorization":
                    continue
                parameter = parameters.get(("header", name))
                if parameter is None:
                    self.error(location, f"canonical request header {name}가 없습니다")
                    continue
                self.compare_schema(
                    raw_property,
                    parameter.get("schema") or {},
                    schemas,
                    f"{location} header {name}",
                )
                if parameter.get("required") is not True:
                    self.error(location, f"canonical request header {name} required가 true가 아닙니다")

    def validate_contract_body(self, operation, catalog, schemas, location):
        body_name = (catalog.get("schemas") or {}).get("body")
        body = operation.get("requestBody")
        if body_name in (None, "none"):
            if body is not None:
                self.error(location, "canonical request body는 none이어야 합니다")
            return
        if not isinstance(body, dict):
            self.error(location, "canonical request body가 없습니다")
            return
        media = (body.get("content") or {}).get("application/json") or {}
        self.compare_schema(
            {"$ref": body_name}, media.get("schema") or {}, schemas, f"{location} request body"
        )

    def validate_contract_success(self, operation, endpoint, schemas, location):
        schema_name = endpoint.get("successSchema")
        if schema_name is None:
            schema_name = endpoint.get("responseSchema")
        for status in endpoint.get("successStatuses", (endpoint.get("responses") or {}).get("success", [])):
            response = self.resolve(
                (operation.get("responses") or {}).get(str(status), {}),
                f"{location} response {status}",
            )
            content = response.get("content") or {}
            if schema_name == "none":
                if content:
                    self.error(location, f"canonical response {status} content는 없어야 합니다")
                continue
            media = content.get("application/json") or {}
            self.compare_schema(
                {"$ref": schema_name},
                media.get("schema") or {},
                schemas,
                f"{location} response {status}",
            )

    def compare_schema(self, raw_expected, raw_actual, schemas, location):
        expected = self.canonical_schema(raw_expected, schemas, location)
        actual = self.resolve(raw_actual, location)
        for key in SCHEMA_CONSTRAINT_KEYS:
            if key == "type":
                expected_value = expected.get("type")
                actual_value = actual.get("type")
                if isinstance(actual_value, list):
                    actual_value = next((item for item in actual_value if item != "null"), None)
            else:
                expected_value = expected.get(key)
                actual_value = actual.get(key)
            if expected_value != actual_value:
                self.error(location, f"schema {key}가 canonical과 다릅니다")
        expected_nullable = expected.get("nullable") is True
        actual_type = actual.get("type")
        actual_nullable = actual.get("nullable") is True or (
            isinstance(actual_type, list) and "null" in actual_type
        )
        if expected_nullable != actual_nullable:
            self.error(location, "schema nullable이 canonical과 다릅니다")
        if set(expected.get("required") or []) != set(actual.get("required") or []):
            self.error(location, "schema required가 canonical과 다릅니다")
        expected_properties = expected.get("properties") or {}
        actual_properties = actual.get("properties") or {}
        if set(expected_properties) != set(actual_properties):
            self.error(location, "schema properties가 canonical과 다릅니다")
        for name in set(expected_properties) & set(actual_properties):
            self.compare_schema(
                expected_properties[name],
                actual_properties[name],
                schemas,
                f"{location}.{name}",
            )
        expected_has_items = "items" in expected
        actual_has_items = "items" in actual
        if expected_has_items != actual_has_items:
            self.error(location, "schema items presence가 canonical과 다릅니다")
        elif expected_has_items:
            self.compare_schema(
                expected["items"], actual["items"], schemas, f"{location}.items"
            )

    def validate_operation_inventory(self):
        required = operations_for_mode(self.mode)
        for key, operation_id in required.items():
            if key not in self.operations:
                prefix = (
                    f"{self.mode}-operation 완료 mode: "
                    if self.mode in (16, 20, 21, 23, 24, 25, 27, 29, 30)
                    else ""
                )
                self.error(f"{key[0]} {key[1]}", prefix + "권위 source의 공개 operation이 없습니다")
        for key in self.operations - set(required):
            self.error(f"{key[0]} {key[1]}", "public inventory allowlist 밖의 endpoint입니다")
        for key in self.operations & set(EXPECTED_OPERATION_IDS):
            actual = self.operation_ids_for_location(key)
            if actual != EXPECTED_OPERATION_IDS[key]:
                self.error(
                    f"{key[0]} {key[1]}",
                    f"operationId는 {EXPECTED_OPERATION_IDS[key]!r}여야 합니다: {actual!r}",
                )

    def operation_ids_for_location(self, key):
        location = f"{key[0]} {key[1]}"
        for operation_id, locations in self.operation_ids.items():
            if location in locations:
                return operation_id
        return None

    def validate_security(self):
        components = self.document.get("components") or {}
        scheme = (components.get("securitySchemes") or {}).get("bearerAuth")
        if not isinstance(scheme, dict) or scheme.get("type") != "http" or scheme.get("scheme") != "bearer":
            self.error("components.securitySchemes.bearerAuth", "HTTP bearer 인증 scheme이 없습니다")
        security = self.document.get("security")
        if not isinstance(security, list) or not any(
            isinstance(requirement, dict) and "bearerAuth" in requirement
            for requirement in security
        ):
            self.error("security", "전역 bearer security 선언이 없습니다")

    def validate_component_schemas(self):
        schemas = ((self.document.get("components") or {}).get("schemas") or {})
        for name, raw_schema in schemas.items():
            schema = self.resolve(raw_schema, f"components.schemas.{name}")
            if schema.get("properties") and schema.get("additionalProperties") is not False:
                self.error(f"components.schemas.{name}", "properties가 있는 schema는 closed object여야 합니다")

    def validate_operation(self, path, method, operation):
        location = f"{method.upper()} {path}"
        self.operations.add((method.upper(), path))
        if not isinstance(operation, dict):
            self.error(location, "operation은 object여야 합니다")
            return
        operation_id = operation.get("operationId")
        if not isinstance(operation_id, str) or not operation_id.strip():
            self.error(location, "operationId가 없습니다")
        elif AUTO_OPERATION_ID.search(operation_id) or not STABLE_OPERATION_ID.fullmatch(operation_id):
            self.error(location, f"operationId {operation_id!r}는 stable lowerCamelCase가 아닙니다")
        else:
            self.operation_ids.setdefault(operation_id, []).append(location)
        for field in ("summary", "description"):
            if not isinstance(operation.get(field), str) or not operation[field].strip():
                self.error(location, f"{field}가 없습니다")
        if not operation.get("tags") or not all(isinstance(tag, str) and tag.strip() for tag in operation["tags"]):
            self.error(location, "frontend domain tag가 없습니다")
        elif any("controller" in tag.lower() for tag in operation["tags"]):
            self.error(location, "Controller 클래스명이 아니라 frontend domain tag를 사용해야 합니다")
        parameters = operation.get("parameters") or []
        self.validate_parameters(parameters, location)
        required_headers = REQUIRED_REQUEST_HEADERS.get((method.upper(), path), set())
        present_headers = {
            self.resolve(parameter, location).get("name")
            for parameter in parameters
            if self.resolve(parameter, location).get("in") == "header"
        }
        for header in sorted(required_headers - present_headers):
            self.error(location, f"필수 {header} header가 없습니다")
        request_body = operation.get("requestBody")
        if request_body is not None:
            self.validate_request_body(request_body, location)
        responses = operation.get("responses")
        if not isinstance(responses, dict) or not responses:
            self.error(location, "responses가 없습니다")
            return
        self.validate_operation_security(path, method, operation, responses, location)
        for status, response in responses.items():
            if not re.fullmatch(r"[1-5](?:\d\d|XX)", str(status)):
                continue
            resolved = self.resolve(response, f"{location} response {status}")
            self.validate_response_headers(path, method, str(status), resolved, location)
            if str(status).startswith("2"):
                self.validate_success_response(str(status), resolved, location)
            elif str(status).startswith(("4", "5")):
                self.validate_problem_response(str(status), resolved, location)

    def validate_operation_security(self, path, method, operation, responses, location):
        key = (method.upper(), path)
        security = operation.get("security")
        bearer = [{"bearerAuth": []}]
        if key in PUBLIC_OPERATIONS:
            if security != []:
                self.error(location, "public security는 빈 배열이어야 합니다")
            return
        if key in OPTIONAL_SECURITY_OPERATIONS:
            if security != [{}, {"bearerAuth": []}]:
                self.error(location, "optional security는 anonymous와 bearer 순서여야 합니다")
            if "403" in responses:
                self.error(location, "optional security operation에는 403 response가 없어야 합니다")
            return
        if security not in (None, bearer):
            self.error(location, "required security는 bearer를 상속하거나 명시해야 합니다")
        if "403" not in responses:
            self.error(location, "required security operation에는 403 response가 필요합니다")

    def validate_response_headers(self, path, method, status, response, location):
        headers = response.get("headers") or {}
        required = REQUIRED_RESPONSE_HEADERS.get((method.upper(), path, status), set())
        expected = {"X-Trace-Id"} | required
        if set(headers) != expected:
            self.error(
                location,
                f"response header projection이 다릅니다: {status} expected={sorted(expected)}, actual={sorted(headers)}",
            )
        for name, raw_header in headers.items():
            header = self.resolve(raw_header, f"{location} response {status} header {name}")
            schema = self.resolve(
                header.get("schema") or {}, f"{location} response {status} header {name}"
            )
            rules = {
                "X-Trace-Id": ("pattern", None),
                "Location": ("format", "uri"),
                "ETag": ("pattern", None),
                "Idempotency-Replayed": ("type", "boolean"),
            }
            if name not in rules:
                if name == "Retry-After" and (
                    schema.get("type") != "integer"
                    or schema.get("minimum", 0) < 1
                    or "IDEMPOTENCY_KEY_REUSED" not in header.get("description", "")
                ):
                    self.error(location, f"response {status} Retry-After 조건부 schema가 올바르지 않습니다")
                continue
            if not header.get("description") or "example" not in header:
                self.error(location, f"response {status} {name} description/example이 없습니다")
            rule, expected = rules[name]
            actual = schema.get(rule)
            if actual is None or (expected is not None and actual != expected):
                self.error(location, f"response {status} {name} schema가 올바르지 않습니다")
            if "example" in header:
                self.validate_schema_value(
                    header["example"], schema, f"{location} response {status} {name} example"
                )

    def validate_parameters(self, parameters, location):
        for raw in parameters:
            parameter = self.resolve(raw, location)
            name = parameter.get("name")
            parameter_location = f"{location} parameter {name}"
            if not parameter.get("description"):
                self.error(parameter_location, "parameter description이 없습니다")
            if not self.parameter_examples(parameter):
                self.error(parameter_location, "parameter example이 없습니다")
            if parameter.get("in") == "path" and parameter.get("required") is not True:
                self.error(parameter_location, "path parameter는 required여야 합니다")
            if parameter.get("in") == "header" and name in {"Idempotency-Key", "If-Match"}:
                schema = self.resolve(parameter.get("schema") or {}, parameter_location)
                if parameter.get("required") is not True:
                    self.error(parameter_location, f"{name} header는 required여야 합니다")
                if not parameter.get("description") or not self.parameter_examples(parameter):
                    self.error(parameter_location, f"{name} header의 description/example이 없습니다")
                if name == "Idempotency-Key" and not (schema.get("format") or schema.get("pattern")):
                    self.error(parameter_location, "Idempotency-Key format/pattern이 없습니다")
                if name == "If-Match" and not schema.get("pattern"):
                    self.error(parameter_location, "If-Match pattern이 없습니다")
            for example in self.parameter_examples(parameter):
                self.validate_schema_value(example, parameter.get("schema") or {}, parameter_location)

    @staticmethod
    def parameter_examples(parameter):
        result = []
        if "example" in parameter:
            result.append(parameter["example"])
        for entry in (parameter.get("examples") or {}).values():
            if isinstance(entry, dict) and "value" in entry:
                result.append(entry["value"])
        schema = parameter.get("schema") or {}
        if "example" in schema:
            result.append(schema["example"])
        return result

    def validate_request_body(self, request_body, location):
        body = self.resolve(request_body, f"{location} requestBody")
        if body.get("required") is not True:
            self.error(location, "requestBody는 required여야 합니다")
        content = body.get("content") or {}
        if "application/json" not in content:
            self.error(location, "JSON requestBody는 application/json이어야 합니다")
            return
        media = content["application/json"]
        examples = self.examples(media)
        if not examples:
            self.error(location, "request example이 없습니다")
        for example in examples:
            self.validate_schema_value(example, media.get("schema") or {}, f"{location} request example")

    def validate_success_response(self, status, response, location):
        content = response.get("content") or {}
        if status == "204":
            if content:
                self.error(location, "204 response는 content가 없어야 합니다")
            return
        if not content:
            self.error(location, f"success response {status} content가 없습니다")
            return
        if "*/*" in content or "application/json" not in content:
            self.error(location, f"success response {status}는 application/json이어야 합니다")
            return
        media = content["application/json"]
        examples = self.examples(media)
        if not examples:
            self.error(location, f"success example이 없습니다: {status}")
        for example in examples:
            self.validate_schema_value(example, media.get("schema") or {}, f"{location} success example {status}")

    def validate_problem_response(self, status, response, location):
        content = response.get("content") or {}
        if "application/problem+json" not in content:
            self.error(location, f"error response {status}는 application/problem+json이어야 합니다")
            return
        media = content["application/problem+json"]
        examples = self.examples(media)
        if not examples:
            self.error(location, f"problem example이 없습니다: {status}")
        for example in examples:
            self.validate_schema_value(example, media.get("schema") or {}, f"{location} problem example {status}")
            if isinstance(example, dict):
                expected = {"type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors"}
                if set(example) != expected:
                    self.error(location, f"problem example shape이 다릅니다: {sorted(set(example) ^ expected)}")
                if isinstance(example.get("status"), int) and status.isdigit() and example["status"] != int(status):
                    self.error(location, f"problem example status {example['status']}가 response {status}와 다릅니다")
                code = example.get("code")
                problem_type = example.get("type")
                if isinstance(code, str) and isinstance(problem_type, str):
                    expected_slug = code.lower().replace("_", "-")
                    if not problem_type.rstrip("/").endswith("/" + expected_slug):
                        self.error(location, "problem example type과 code가 서로 다릅니다")
                trace_id = example.get("traceId")
                instance = example.get("instance")
                if isinstance(trace_id, str) and isinstance(instance, str) and not instance.endswith(trace_id):
                    self.error(location, "problem example instance와 traceId가 서로 다릅니다")

    def validate_schema_value(self, value, raw_schema, location):
        schema = self.resolve(raw_schema, location)
        if not schema:
            self.error(location, "example schema가 없습니다")
            return
        if "allOf" in schema:
            for index, child in enumerate(schema["allOf"]):
                self.validate_schema_value(value, child, f"{location}.allOf[{index}]")
            return
        alternatives = schema.get("oneOf") or schema.get("anyOf")
        if alternatives:
            matches = 0
            for child in alternatives:
                before = len(self.errors)
                self.validate_schema_value(value, child, location)
                if len(self.errors) == before:
                    matches += 1
                else:
                    del self.errors[before:]
            if matches == 0:
                self.error(location, "oneOf/anyOf schema 중 example과 일치하는 항목이 없습니다")
            return
        nullable = schema.get("nullable") is True
        schema_type = schema.get("type")
        allowed_types = schema_type if isinstance(schema_type, list) else [schema_type]
        if value is None:
            if nullable or "null" in allowed_types:
                return
            self.error(location, "null example이 nullable schema가 아닙니다")
            return
        allowed_types = [item for item in allowed_types if item and item != "null"]
        if allowed_types and not any(self.matches_type(value, item) for item in allowed_types):
            self.error(location, f"example type이 schema type {allowed_types}과 다릅니다")
            return
        if "enum" in schema and value not in schema["enum"]:
            self.error(location, f"example이 enum에 없습니다: {value!r}")
        if isinstance(value, str):
            if "minLength" in schema and len(value) < schema["minLength"]:
                self.error(location, "example이 minLength보다 짧습니다")
            if "maxLength" in schema and len(value) > schema["maxLength"]:
                self.error(location, "example이 maxLength보다 깁니다")
            if schema.get("pattern"):
                try:
                    if re.fullmatch(schema["pattern"], value) is None:
                        self.error(location, "example이 pattern과 다릅니다")
                except re.error:
                    self.error(location, "schema pattern이 유효한 정규식이 아닙니다")
            self.validate_format(value, schema.get("format"), location)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            self.validate_number(value, schema, location)
        if isinstance(value, list):
            if "minItems" in schema and len(value) < schema["minItems"]:
                self.error(location, "example array가 minItems보다 짧습니다")
            if "maxItems" in schema and len(value) > schema["maxItems"]:
                self.error(location, "example array가 maxItems보다 깁니다")
            if schema.get("uniqueItems") and len({json.dumps(item, sort_keys=True) for item in value}) != len(value):
                self.error(location, "example array가 uniqueItems가 아닙니다")
            for index, item in enumerate(value):
                self.validate_schema_value(item, schema.get("items") or {}, f"{location}[{index}]")
        if isinstance(value, dict):
            properties = schema.get("properties") or {}
            required = set(schema.get("required") or [])
            missing = required - set(value)
            for key in sorted(missing):
                self.error(location, f"required property {key!r} example이 없습니다")
            if schema.get("additionalProperties") is False:
                for key in sorted(set(value) - set(properties)):
                    self.error(location, f"additional property {key!r}가 schema에 없습니다")
            undocumented = set(properties) - set(value)
            for key in sorted(undocumented):
                self.error(location, f"schema property {key!r}를 example이 보여주지 않습니다")
            for key, item in value.items():
                if key in properties:
                    self.validate_schema_value(item, properties[key], f"{location}.{key}")

    @staticmethod
    def matches_type(value, schema_type):
        return {
            "object": isinstance(value, dict),
            "array": isinstance(value, list),
            "string": isinstance(value, str),
            "integer": isinstance(value, int) and not isinstance(value, bool),
            "number": isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value),
            "boolean": isinstance(value, bool),
        }.get(schema_type, True)

    def validate_format(self, value, value_format, location):
        valid = True
        if value_format == "uuid":
            valid = UUID.fullmatch(value) is not None
        elif value_format == "date":
            try:
                valid = DATE.fullmatch(value) is not None and date.fromisoformat(value) is not None
            except ValueError:
                valid = False
        elif value_format == "date-time":
            try:
                valid = DATE_TIME.fullmatch(value) is not None and datetime.fromisoformat(value.replace("Z", "+00:00")) is not None
            except ValueError:
                valid = False
        elif value_format == "uri":
            parsed = urlparse(value)
            valid = bool(parsed.scheme or value.startswith("/"))
        if not valid:
            self.error(location, f"example이 {value_format} format과 다릅니다")

    def validate_number(self, value, schema, location):
        if "minimum" in schema and value < schema["minimum"]:
            self.error(location, "example이 minimum보다 작습니다")
        if "maximum" in schema and value > schema["maximum"]:
            self.error(location, "example이 maximum보다 큽니다")
        if "exclusiveMinimum" in schema and value <= schema["exclusiveMinimum"]:
            self.error(location, "example이 exclusiveMinimum 이하여야 할 수 없습니다")
        if "exclusiveMaximum" in schema and value >= schema["exclusiveMaximum"]:
            self.error(location, "example이 exclusiveMaximum 이상이어야 할 수 없습니다")

    def scan_examples(self, value, location, in_example=False):
        if isinstance(value, dict):
            for key, child in value.items():
                child_is_example = in_example or key in {"example", "examples"}
                self.scan_examples(child, f"{location}.{key}", child_is_example)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                self.scan_examples(child, f"{location}[{index}]", in_example)
        elif in_example and isinstance(value, str) and SECRET_LIKE.search(value):
            self.error(location, "secret-like example이 포함되었습니다")

    def validate_known_headers(self):
        headers = ((self.document.get("components") or {}).get("headers") or {})
        rules = {
            "Location": ("format", "uri"),
            "ETag": ("pattern", None),
            "If-Match": ("pattern", None),
            "Idempotency-Key": ("pattern-or-format", None),
            "Idempotency-Replayed": ("type", "boolean"),
            "TraceId": ("pattern", None),
            "X-Trace-Id": ("pattern", None),
        }
        for name, (rule, expected) in rules.items():
            if name not in headers:
                continue
            header = self.resolve(headers[name], f"components.headers.{name}")
            schema = self.resolve(header.get("schema") or {}, f"components.headers.{name}")
            if not header.get("description") or "example" not in header:
                self.error(f"components.headers.{name}", f"{name} description/example이 없습니다")
            if header.get("required") is not True:
                self.error(f"components.headers.{name}", f"{name} required가 true가 아닙니다")
            if rule == "pattern-or-format" and not (schema.get("pattern") or schema.get("format")):
                self.error(f"components.headers.{name}", f"{name} pattern/format이 없습니다")
            elif rule != "pattern-or-format" and (schema.get(rule) is None or (expected is not None and schema.get(rule) != expected)):
                self.error(f"components.headers.{name}", f"{name} {rule}이 올바르지 않습니다")
            if "example" in header:
                self.validate_schema_value(header["example"], schema, f"components.headers.{name} example")
        component_parameters = ((self.document.get("components") or {}).get("parameters") or {})
        self.validate_parameters(component_parameters.values(), "components.parameters")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "artifact",
        nargs="?",
        type=Path,
        default=Path("services/spring-api/build/openapi/openapi.json"),
    )
    parser.add_argument(
        "--mode",
        type=int,
        choices=(9, 16, 20, 21, 23, 24, 25, 27, 29, 30),
        default=30,
        help="historical modes {9,16,20,21,23,24,25,27,29}; active mode30",
    )
    parser.add_argument("--contracts-root", type=Path, default=Path.cwd())
    args = parser.parse_args(argv[1:])
    artifact = args.artifact
    if not artifact.is_file():
        print(f"OpenAPI artifact가 없습니다: {artifact}", file=sys.stderr)
        return 1
    try:
        document = json.loads(artifact.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        print(f"OpenAPI artifact를 읽을 수 없습니다: {error}", file=sys.stderr)
        return 1
    errors = Validator(document, args.mode, args.contracts_root).validate()
    if errors:
        print("OpenAPI frontend-readiness 검사 실패:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    operations = sum(
        1
        for path_item in document["paths"].values()
        for method in path_item
        if method.lower() in HTTP_METHODS
    )
    print(f"OpenAPI frontend-readiness 검사 성공: {operations} operations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
