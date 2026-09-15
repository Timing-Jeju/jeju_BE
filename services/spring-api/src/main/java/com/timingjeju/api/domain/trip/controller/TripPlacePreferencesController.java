package com.timingjeju.api.domain.trip.controller;

import com.timingjeju.api.application.idempotency.*;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPlacePreferencesMutation;
import com.timingjeju.api.application.trip.service.TripPlacePreferencesService;
import com.timingjeju.api.domain.trip.controller.docs.TripPlacePreferencesApiDocs;
import com.timingjeju.api.domain.trip.dto.request.UpdateTripPlacePreferencesRequest;
import com.timingjeju.api.domain.trip.dto.response.TripPlacePreferencesResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@RestController
@RequestMapping("/api/v1/trips")
public class TripPlacePreferencesController implements TripPlacePreferencesApiDocs {
  private static final Pattern CANONICAL_UUID = Pattern.compile(UUID_PATTERN);

  private final TripPlacePreferencesService service;
  private final CurrentUserAccessor currentUsers;
  private final ObjectReader requestReader;
  private final ObjectMapper objectMapper;
  private final IdempotencyUseCase receipts;
  private final com.timingjeju.api.application.trip.service.TripService trips;

  public TripPlacePreferencesController(
      TripPlacePreferencesService service,
      CurrentUserAccessor currentUsers,
      ObjectMapper objectMapper,
      IdempotencyUseCase receipts,
      com.timingjeju.api.application.trip.service.TripService trips) {
    this.service = service;
    this.currentUsers = currentUsers;
    this.objectMapper = objectMapper;
    this.receipts = receipts;
    this.trips = trips;
    this.requestReader =
        objectMapper
            .rebuild()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
            .readerFor(UpdateTripPlacePreferencesRequest.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Override
  @PutMapping(value = "/{tripId}/place-preferences", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> replace(
      @PathVariable String tripId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) {
      throw TripException.invalidRequest();
    }
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    try {
      TripEntityTag.parse(ifMatch);
    } catch (TripException failure) {
      throw TripException.invalidRequest();
    }
    UpdateTripPlacePreferencesRequest parsed = parse(readRequiredJsonBody(request));
    if (parsed == null) {
      throw TripException.invalidRequest();
    }
    var user = currentUsers.getRequired();
    var command = parsed.toCommand();
    var keys = request.getHeaders("Idempotency-Key");
    if (keys == null || !keys.hasMoreElements()) {
      TripPlacePreferencesMutation result =
          service.replace(user, canonicalTripId, ifMatch, command);
      return ResponseEntity.ok()
          .header(HttpHeaders.ETAG, result.etag())
          .body(objectMapper.writeValueAsBytes(TripPlacePreferencesResponse.from(result)));
    }
    String key = keys.nextElement();
    if (keys.hasMoreElements() || key == null || key.isBlank())
      throw IdempotencyException.invalid();
    if (!canonicalTripId.equals(TripEntityTag.parse(ifMatch).tripId()))
      throw TripException.versionConflict();
    var input =
        IdempotencyRequest.create(
            user.userId(),
            "PUT",
            "/api/v1/trips/" + canonicalTripId + "/place-preferences",
            key,
            objectMapper.writeValueAsBytes(command));
    // Receipt에 기록된 과거 소유권으로 현재 접근 권한을 대신하지 않는다.
    trips.read(user, canonicalTripId);
    var replayed = new java.util.concurrent.atomic.AtomicBoolean(true);
    var result =
        receipts.execute(
            input,
            () -> {
              replayed.set(false);
              var mutation = service.replace(user, canonicalTripId, ifMatch, command);
              return new IdempotencyResponse(
                  200,
                  java.util.List.of(
                      new IdempotencyHeader(
                          HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE),
                      new IdempotencyHeader(HttpHeaders.ETAG, mutation.etag())),
                  objectMapper.writeValueAsBytes(TripPlacePreferencesResponse.from(mutation)));
            });
    var response = ResponseEntity.status(result.status());
    result.headers().forEach(header -> response.header(header.name(), header.value()));
    return response
        .header("Idempotency-Replayed", Boolean.toString(replayed.get()))
        .body(result.body());
  }

  private static byte[] readRequiredJsonBody(HttpServletRequest request) {
    String contentType = request.getContentType();
    if (contentType == null) {
      throw TripException.invalidRequest();
    }
    try {
      if (!MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) {
        throw TripException.invalidRequest();
      }
      byte[] body = request.getInputStream().readNBytes((1024 * 1024) + 1);
      if (body.length == 0 || body.length > 1024 * 1024) {
        throw TripException.invalidRequest();
      }
      long advertisedLength = request.getContentLengthLong();
      if (advertisedLength >= 0 && advertisedLength != body.length) {
        throw TripException.invalidRequest();
      }
      return body;
    } catch (IllegalArgumentException | IOException failure) {
      throw TripException.invalidRequest();
    }
  }

  private UpdateTripPlacePreferencesRequest parse(byte[] body) {
    try {
      return requestReader.readValue(body);
    } catch (JacksonException failure) {
      throw TripException.invalidRequest();
    }
  }

  private static UUID parseCanonicalUuid(String raw) {
    if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
      throw TripException.invalidRequest();
    }
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equals(raw)) {
        throw TripException.invalidRequest();
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw TripException.invalidRequest();
    }
  }
}
