package com.timingjeju.api.domain.trip.controller;

import com.timingjeju.api.application.idempotency.IdempotencyHeader;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyResponse;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.service.TripService;
import com.timingjeju.api.domain.trip.controller.docs.TripApiDocs;
import com.timingjeju.api.domain.trip.dto.request.CreateTripRequest;
import com.timingjeju.api.domain.trip.dto.response.TripAggregateResponse;
import com.timingjeju.api.domain.trip.dto.response.TripListResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/trips")
public class TripController implements TripApiDocs {
  private static final Set<String> LIST_PARAMETERS = Set.of("status", "sort", "cursor", "size");
  private static final Pattern CANONICAL_UUID = Pattern.compile(TripApiDocs.UUID_PATTERN);
  private final TripService trips;
  private final CurrentUserAccessor currentUsers;
  private final IdempotencyUseCase idempotency;
  private final ObjectMapper objectMapper;

  public TripController(
      TripService trips,
      CurrentUserAccessor currentUsers,
      IdempotencyUseCase idempotency,
      ObjectMapper objectMapper) {
    this.trips = trips;
    this.currentUsers = currentUsers;
    this.idempotency = idempotency;
    this.objectMapper = objectMapper;
  }

  @Override
  @GetMapping
  public TripListResponse list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size,
      HttpServletRequest request) {
    validateListParameters(request);
    return TripListResponse.from(
        trips.list(currentUsers.getRequired(), status, sort, cursor, size));
  }

  @Override
  @PostMapping
  public ResponseEntity<byte[]> create(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody byte[] body) {
    CurrentUser user = currentUsers.getRequired();
    IdempotencyRequest request;
    try {
      request =
          IdempotencyRequest.create(user.userId(), "POST", "/api/v1/trips", idempotencyKey, body);
    } catch (IllegalArgumentException failure) {
      throw TripException.invalidRequest();
    }
    AtomicBoolean replayed = new AtomicBoolean(true);
    IdempotencyResponse result =
        idempotency.execute(
            request,
            () -> {
              replayed.set(false);
              TripAggregateResponse response =
                  TripAggregateResponse.from(trips.create(user, parse(body)));
              String etag = TripEntityTag.strong(response.tripId(), response.updatedAt());
              byte[] responseBody = serialize(response);
              return new IdempotencyResponse(
                  201,
                  List.of(
                      new IdempotencyHeader(
                          HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE),
                      new IdempotencyHeader(
                          HttpHeaders.LOCATION, "/api/v1/trips/" + response.tripId()),
                      new IdempotencyHeader(HttpHeaders.ETAG, etag)),
                  responseBody);
            });
    ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
    result.headers().forEach(header -> response.header(header.name(), header.value()));
    response.header("Idempotency-Replayed", Boolean.toString(replayed.get()));
    return response.body(result.body());
  }

  @Override
  @GetMapping("/{tripId}")
  public TripAggregateResponse read(@PathVariable String tripId) {
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    return TripAggregateResponse.from(trips.read(currentUsers.getRequired(), canonicalTripId));
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

  private com.timingjeju.api.application.trip.CreateTripCommand parse(byte[] body) {
    try {
      return objectMapper.readValue(body, CreateTripRequest.class).toCommand();
    } catch (JacksonException failure) {
      throw TripException.invalidRequest();
    }
  }

  private byte[] serialize(TripAggregateResponse response) {
    try {
      return objectMapper.writeValueAsBytes(response);
    } catch (JacksonException failure) {
      throw TripException.dataUnavailable();
    }
  }

  private static void validateListParameters(HttpServletRequest request) {
    if (!LIST_PARAMETERS.containsAll(request.getParameterMap().keySet())
        || request.getParameterMap().keySet().stream()
            .anyMatch(name -> request.getParameterValues(name).length != 1)) {
      throw TripException.invalidQuery();
    }
  }
}
