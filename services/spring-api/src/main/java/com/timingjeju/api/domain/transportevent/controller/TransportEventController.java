package com.timingjeju.api.domain.transportevent.controller;

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

  public TransportEventController(
      TransportEventService events, CurrentUserAccessor currentUsers, ObjectMapper objectMapper) {
    this.events = events;
    this.currentUsers = currentUsers;
    this.objectMapper = objectMapper;
  }

  @Override
  @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<TransportEventMutationPayload> put(
      @PathVariable String tripId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request) {
    validateNoParameters(request);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    TransportEventMutationPayload payload =
        events.put(
            currentUsers.getRequired().userId(),
            canonicalTripId,
            expected(ifMatch),
            parse(TransportEventRequestBoundary.readRequiredJson(request)).toCommand());
    return ResponseEntity.ok().eTag(payload.etag()).body(payload);
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
      return objectMapper
          .readerFor(PutTransportEventRequest.class)
          .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readValue(body);
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
