package com.timingjeju.api.domain.accommodation.controller;

import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.accommodation.AccommodationHttpResult;
import com.timingjeju.api.application.accommodation.service.AccommodationService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
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
      HttpServletRequest request) {
    AccommodationRequestBoundary.requireNoQuery(request);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    String canonicalKey =
        AccommodationRequestBoundary.requiredPrintableAsciiHeader(request, "Idempotency-Key");
    String canonicalIfMatch =
        AccommodationRequestBoundary.requiredSingleHeader(request, HttpHeaders.IF_MATCH);
    long expectedRevision = expectedRevision(canonicalTripId, canonicalIfMatch);
    CreateAccommodationRequest body =
        parseCreate(AccommodationRequestBoundary.readRequiredBody(request));
    CurrentUser current = currentUsers.getRequired();
    AccommodationHttpResult result =
        accommodations.create(
            current.userId(), canonicalTripId, canonicalKey, expectedRevision, body.toCommand());
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
      HttpServletRequest request) {
    AccommodationRequestBoundary.requireNoQuery(request);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    UUID canonicalAccommodationId = parseCanonicalUuid(accommodationId);
    String canonicalIfMatch =
        AccommodationRequestBoundary.requiredSingleHeader(request, HttpHeaders.IF_MATCH);
    long expectedRevision = expectedRevision(canonicalTripId, canonicalIfMatch);
    PatchAccommodationRequest body =
        parsePatch(AccommodationRequestBoundary.readRequiredBody(request));
    AccommodationHttpResult result =
        accommodations.patch(
            currentUsers.getRequired().userId(),
            canonicalTripId,
            canonicalAccommodationId,
            expectedRevision,
            body.toCommand());
    return response(result);
  }

  @Override
  @DeleteMapping("/{accommodationId}")
  public ResponseEntity<Void> delete(
      @PathVariable String tripId,
      @PathVariable String accommodationId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request) {
    AccommodationRequestBoundary.requireEmptyDelete(request);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    UUID canonicalAccommodationId = parseCanonicalUuid(accommodationId);
    String canonicalIfMatch =
        AccommodationRequestBoundary.requiredSingleHeader(request, HttpHeaders.IF_MATCH);
    long expectedRevision = expectedRevision(canonicalTripId, canonicalIfMatch);
    accommodations.delete(
        currentUsers.getRequired().userId(),
        canonicalTripId,
        canonicalAccommodationId,
        expectedRevision);
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
    try {
      return objectMapper
          .readerFor(CreateAccommodationRequest.class)
          .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .readValue(body);
    } catch (JacksonException | AccommodationException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  private PatchAccommodationRequest parsePatch(byte[] body) {
    try {
      return objectMapper
          .readerFor(PatchAccommodationRequest.class)
          .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .readValue(body);
    } catch (JacksonException | AccommodationException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  private static long expectedRevision(UUID canonicalTripId, String ifMatch) {
    try {
      var expected = TripEntityTag.parse(ifMatch);
      if (!expected.tripId().equals(canonicalTripId)) {
        throw AccommodationException.of("TRIP_VERSION_CONFLICT");
      }
      return expected.revision();
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
}
