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
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/trips")
public class TripPlacePreferencesController implements TripPlacePreferencesApiDocs {
  private static final Pattern CANONICAL_UUID = Pattern.compile(UUID_PATTERN);
  private static final Pattern STRONG_ETAG = Pattern.compile("^\"[A-Za-z0-9._:-]{1,128}\"$");

  private final TripPlacePreferencesService service;
  private final CurrentUserAccessor currentUsers;
  private final ObjectMapper objectMapper;

  public TripPlacePreferencesController(
      TripPlacePreferencesService service,
      CurrentUserAccessor currentUsers,
      ObjectMapper objectMapper) {
    this.service = service;
    this.currentUsers = currentUsers;
    this.objectMapper = objectMapper;
  }

  @Override
  @PutMapping(
      value = "/{tripId}/place-preferences",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<TripPlacePreferencesResponse> replace(
      @PathVariable String tripId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody byte[] body,
      HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()
        || ifMatch == null
        || !STRONG_ETAG.matcher(ifMatch).matches()) {
      throw TripException.invalidRequest();
    }
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    TripPlacePreferencesMutation result =
        service.replace(
            currentUsers.getRequired(), canonicalTripId, ifMatch, parse(body).toCommand());
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, TripEntityTag.strong(result.tripId(), result.updatedAt()))
        .body(TripPlacePreferencesResponse.from(result));
  }

  private UpdateTripPlacePreferencesRequest parse(byte[] body) {
    try {
      return objectMapper.readValue(body, UpdateTripPlacePreferencesRequest.class);
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
