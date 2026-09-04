package com.timingjeju.api.domain.schedule.controller;

import com.timingjeju.api.application.idempotency.IdempotencyHeader;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyResponse;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.schedule.service.ScheduleMutationService;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.domain.schedule.controller.docs.ScheduleMutationApiDocs;
import com.timingjeju.api.domain.schedule.dto.CreateScheduleItemRequest;
import com.timingjeju.api.domain.schedule.dto.ScheduleMutationResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/schedule-items")
public final class ScheduleMutationController implements ScheduleMutationApiDocs {
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final Set<String> NON_NULL_OPTIONAL_FIELDS =
      Set.of(
          "placeId",
          "accommodationId",
          "transportEventId",
          "title",
          "bufferAfterMinutes",
          "required");
  private static final Set<String> REQUIRED_FIELDS =
      Set.of(
          "expectedActiveScheduleVersionId",
          "dayNo",
          "sequenceNo",
          "itemType",
          "plannedStartAt",
          "stayMinutes");

  private final ScheduleMutationService schedules;
  private final CurrentUserAccessor currentUsers;
  private final IdempotencyUseCase idempotency;
  private final ObjectMapper objectMapper;
  private final ObjectReader strictReader;

  public ScheduleMutationController(
      ScheduleMutationService schedules,
      CurrentUserAccessor currentUsers,
      IdempotencyUseCase idempotency,
      ObjectMapper objectMapper) {
    this.schedules = schedules;
    this.currentUsers = currentUsers;
    this.idempotency = idempotency;
    this.objectMapper = objectMapper;
    this.strictReader =
        objectMapper
            .readerFor(CreateScheduleItemRequest.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Override
  @PostMapping
  public ResponseEntity<byte[]> addItem(
      @PathVariable String tripId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody byte[] body,
      HttpServletRequest servletRequest) {
    if (!servletRequest.getParameterMap().isEmpty()) {
      throw ScheduleException.invalidRequest();
    }
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    var expectedTrip = TripEntityTag.parse(ifMatch);
    CurrentUser user = currentUsers.getRequired();
    validateBodySize(body);
    IdempotencyRequest request =
        IdempotencyRequest.create(
            user.userId(),
            "POST",
            "/api/v1/trips/" + canonicalTripId + "/schedule-items",
            idempotencyKey,
            body);
    AtomicBoolean replayed = new AtomicBoolean(true);
    IdempotencyResponse result =
        idempotency.execute(
            request,
            () -> {
              replayed.set(false);
              var mutation =
                  schedules.addItem(
                      user, canonicalTripId, expectedTrip, parseRequest(body).toCommand());
              ScheduleMutationResponse response = ScheduleMutationResponse.from(mutation);
              return new IdempotencyResponse(
                  201,
                  List.of(
                      new IdempotencyHeader(
                          HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE),
                      new IdempotencyHeader(HttpHeaders.ETAG, response.etag())),
                  serialize(response));
            });
    ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
    result.headers().forEach(header -> response.header(header.name(), header.value()));
    response.header("Idempotency-Replayed", Boolean.toString(replayed.get()));
    return response.body(result.body());
  }

  private CreateScheduleItemRequest parseRequest(byte[] body) {
    validateBodySize(body);
    try {
      CreateScheduleItemRequest request = strictReader.readValue(body);
      JsonNode tree = objectMapper.readTree(body);
      if (tree == null
          || !tree.isObject()
          || REQUIRED_FIELDS.stream().anyMatch(name -> !tree.has(name) || tree.get(name).isNull())
          || NON_NULL_OPTIONAL_FIELDS.stream()
              .anyMatch(name -> tree.has(name) && tree.get(name).isNull())) {
        throw ScheduleException.invalidRequest();
      }
      return request;
    } catch (JacksonException failure) {
      throw ScheduleException.invalidRequest();
    }
  }

  private static void validateBodySize(byte[] body) {
    if (body == null || body.length > IdempotencyRequest.MAX_BODY_BYTES) {
      throw ScheduleException.invalidRequest();
    }
  }

  private byte[] serialize(ScheduleMutationResponse response) {
    try {
      return objectMapper.writeValueAsBytes(response);
    } catch (JacksonException failure) {
      throw ScheduleException.internalServerError();
    }
  }

  private static UUID parseCanonicalUuid(String raw) {
    if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
      throw ScheduleException.invalidRequest();
    }
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equals(raw)) {
        throw ScheduleException.invalidRequest();
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw ScheduleException.invalidRequest();
    }
  }
}
