package com.timingjeju.api.domain.transportevent.controller;

import com.timingjeju.api.application.idempotency.*;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.transportevent.TransportEventException;
import com.timingjeju.api.application.transportevent.TransportEventMutationPayload;
import com.timingjeju.api.application.transportevent.service.TransportEventService;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import com.timingjeju.api.domain.transportevent.controller.docs.TransportEventApiDocs;
import com.timingjeju.api.domain.transportevent.dto.request.PutTransportEventRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/transport-event")
public class TransportEventController implements TransportEventApiDocs {
  private static final Pattern CANONICAL_UUID = Pattern.compile(TransportEventApiDocs.UUID_PATTERN);

  private final TransportEventService events;
  private final CurrentUserAccessor currentUsers;
  private final ObjectMapper objectMapper;
  private final IdempotencyUseCase receipts;

  public TransportEventController(
      TransportEventService events,
      CurrentUserAccessor currentUsers,
      ObjectMapper objectMapper,
      IdempotencyUseCase receipts) {
    this.events = events;
    this.currentUsers = currentUsers;
    this.objectMapper = objectMapper;
    this.receipts = receipts;
  }

  @Override
  @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> put(
      @PathVariable String tripId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request) {
    validateNoParameters(request);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    var owner = currentUsers.getRequired().userId();
    var revision = expected(ifMatch);
    var command = parse(TransportEventRequestBoundary.readRequiredJson(request)).toCommand();
    var headers = request.getHeaders("Idempotency-Key");
    if (headers == null || !headers.hasMoreElements()) {
      var payload = events.put(owner, canonicalTripId, revision, command);
      return ResponseEntity.ok().eTag(payload.etag()).body(objectMapper.writeValueAsBytes(payload));
    }
    String key = headers.nextElement();
    if (headers.hasMoreElements() || key == null || key.isBlank())
      throw IdempotencyException.invalid();
    if (!canonicalTripId.equals(revision.tripId()))
      throw TransportEventException.of("TRIP_VERSION_CONFLICT");
    var input =
        IdempotencyRequest.create(
            owner,
            "PUT",
            "/api/v1/trips/" + canonicalTripId + "/transport-event",
            key,
            objectMapper.writeValueAsBytes(command));
    // 과거 receipt도 현재 owner 확인을 통과한 요청에만 반환한다.
    events.requireOwned(owner, canonicalTripId);
    var replayed = new java.util.concurrent.atomic.AtomicBoolean(true);
    var result =
        receipts.execute(
            input,
            () -> {
              replayed.set(false);
              var payload = events.put(owner, canonicalTripId, revision, command);
              return new IdempotencyResponse(
                  200,
                  java.util.List.of(
                      new IdempotencyHeader(
                          HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE),
                      new IdempotencyHeader(HttpHeaders.ETAG, payload.etag())),
                  objectMapper.writeValueAsBytes(payload));
            });
    var response = ResponseEntity.status(result.status());
    result.headers().forEach(header -> response.header(header.name(), header.value()));
    return response
        .header("Idempotency-Replayed", Boolean.toString(replayed.get()))
        .body(result.body());
  }

  @Override
  @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<TransportEventMutationPayload> delete(
      @PathVariable String tripId,
      @RequestParam(name = "eventType", required = false) String eventType,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request) {
    validateDeleteRequest(request);
    TransportEventRequestBoundary.requireEmptyDelete(request);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    TransportEventMutationPayload payload =
        events.delete(
            currentUsers.getRequired().userId(), canonicalTripId, eventType, expected(ifMatch));
    return ResponseEntity.ok().eTag(payload.etag()).body(payload);
  }

  private PutTransportEventRequest parse(byte[] body) {
    try {
      PutTransportEventRequest parsed =
          objectMapper
              .readerFor(PutTransportEventRequest.class)
              .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(body);
      if (parsed == null) throw TransportEventException.invalidRequest();
      return parsed;
    } catch (JacksonException | TransportEventException failure) {
      throw TransportEventException.invalidRequest();
    }
  }

  private static TripExpectedRevision expected(String raw) {
    try {
      return TripEntityTag.parse(raw);
    } catch (TripException failure) {
      throw TransportEventException.invalidRequest();
    }
  }

  private static UUID parseCanonicalUuid(String raw) {
    if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
      throw TransportEventException.invalidRequest();
    }
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equals(raw)) throw TransportEventException.invalidRequest();
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw TransportEventException.invalidRequest();
    }
  }

  private static void validateNoParameters(HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) throw TransportEventException.invalidRequest();
  }

  private static void validateDeleteRequest(HttpServletRequest request) {
    String[] values = request.getParameterMap().get("eventType");
    if (request.getParameterMap().size() != 1
        || values == null
        || values.length != 1
        || values[0] == null
        || !java.util.Set.of("arrival", "departure").contains(values[0])) {
      throw TransportEventException.invalidRequest();
    }
  }
}
