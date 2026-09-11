package com.timingjeju.api.domain.trip.controller;

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

  public TripPlacePreferencesController(
      TripPlacePreferencesService service,
      CurrentUserAccessor currentUsers,
      ObjectMapper objectMapper) {
    this.service = service;
    this.currentUsers = currentUsers;
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
  public ResponseEntity<TripPlacePreferencesResponse> replace(
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
    TripPlacePreferencesMutation result =
        service.replace(currentUsers.getRequired(), canonicalTripId, ifMatch, parsed.toCommand());
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, result.etag())
        .body(TripPlacePreferencesResponse.from(result));
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
