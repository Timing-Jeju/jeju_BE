package com.timingjeju.api.domain.accommodation.controller;

import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.accommodation.AccommodationHttpResult;
import com.timingjeju.api.application.accommodation.service.AccommodationService;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.domain.accommodation.controller.docs.AccommodationApiDocs;
import com.timingjeju.api.domain.accommodation.dto.request.CreateAccommodationRequest;
import com.timingjeju.api.domain.accommodation.dto.request.PatchAccommodationRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/accommodations")
public class AccommodationController implements AccommodationApiDocs {
  private static final Pattern CANONICAL_UUID = Pattern.compile(AccommodationApiDocs.UUID_PATTERN);

  private final AccommodationService accommodations;
  private final CurrentUserAccessor currentUsers;
  private final ObjectMapper objectMapper;

  public AccommodationController(
      AccommodationService accommodations,
      CurrentUserAccessor currentUsers,
      ObjectMapper objectMapper) {
    this.accommodations = accommodations;
    this.currentUsers = currentUsers;
    this.objectMapper = objectMapper;
  }

  @Override
  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> create(
      @PathVariable String tripId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody byte[] body,
      HttpServletRequest request) {
    validateNoParameters(request);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    CurrentUser current = currentUsers.getRequired();
    AccommodationHttpResult result =
        accommodations.create(
            current.userId(),
            canonicalTripId,
            key,
            expected(ifMatch),
            parseCreate(body).toCommand());
    return response(result);
  }

  @Override
  @PatchMapping(
      path = "/{accommodationId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> patch(
      @PathVariable String tripId,
      @PathVariable String accommodationId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody byte[] body,
      HttpServletRequest request) {
    validateNoParameters(request);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    UUID canonicalAccommodationId = parseCanonicalUuid(accommodationId);
    AccommodationHttpResult result =
        accommodations.patch(
            currentUsers.getRequired().userId(),
            canonicalTripId,
            canonicalAccommodationId,
            expected(ifMatch),
            parsePatch(body).toCommand());
    return response(result);
  }

  @Override
  @DeleteMapping("/{accommodationId}")
  public ResponseEntity<Void> delete(
      @PathVariable String tripId,
      @PathVariable String accommodationId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) byte[] body,
      HttpServletRequest request) {
    validateNoParameters(request);
    if (body != null && body.length > 0) {
      throw AccommodationException.invalidRequest();
    }
    accommodations.delete(
        currentUsers.getRequired().userId(),
        parseCanonicalUuid(tripId),
        parseCanonicalUuid(accommodationId),
        expected(ifMatch));
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<byte[]> response(AccommodationHttpResult result) {
    var snapshot = result.snapshot();
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(snapshot.status())
            .contentType(MediaType.parseMediaType(snapshot.contentType()))
            .eTag(snapshot.etag());
    if (snapshot.location() != null) {
      response.header(HttpHeaders.LOCATION, snapshot.location());
      response.header("Idempotency-Replayed", Boolean.toString(result.replayed()));
    }
    return response.body(snapshot.body());
  }

  private CreateAccommodationRequest parseCreate(byte[] body) {
    validateBodySize(body);
    try {
      return objectMapper.readValue(body, CreateAccommodationRequest.class);
    } catch (JacksonException | AccommodationException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  private PatchAccommodationRequest parsePatch(byte[] body) {
    validateBodySize(body);
    try {
      return objectMapper.readValue(body, PatchAccommodationRequest.class);
    } catch (JacksonException | AccommodationException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  private static void validateBodySize(byte[] body) {
    if (body == null || body.length == 0 || body.length > IdempotencyRequest.MAX_BODY_BYTES) {
      throw AccommodationException.invalidRequest();
    }
  }

  private static com.timingjeju.api.application.trip.TripExpectedRevision expected(String raw) {
    try {
      return TripEntityTag.parse(raw);
    } catch (TripException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  private static UUID parseCanonicalUuid(String raw) {
    if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
      throw AccommodationException.invalidRequest();
    }
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equals(raw)) {
        throw AccommodationException.invalidRequest();
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  private static void validateNoParameters(HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) {
      throw AccommodationException.invalidRequest();
    }
  }
}
