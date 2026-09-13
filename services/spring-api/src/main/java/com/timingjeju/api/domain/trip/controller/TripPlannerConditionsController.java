package com.timingjeju.api.domain.trip.controller;

import com.timingjeju.api.application.idempotency.IdempotencyHeader;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyResponse;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.service.TripPlannerConditionsService;
import com.timingjeju.api.domain.trip.controller.docs.TripPlannerConditionsApiDocs;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/trips")
public class TripPlannerConditionsController implements TripPlannerConditionsApiDocs {
  private final TripPlannerConditionsService store;
  private final CurrentUserAccessor users;
  private final IdempotencyUseCase receipts;
  private final ObjectMapper mapper;
  private final TripPlannerConditionsCodec codec;

  public TripPlannerConditionsController(
      TripPlannerConditionsService store,
      CurrentUserAccessor users,
      IdempotencyUseCase receipts,
      ObjectMapper mapper) {
    this.store = store;
    this.users = users;
    this.receipts = receipts;
    this.mapper = mapper;
    this.codec = new TripPlannerConditionsCodec(mapper);
  }

  @Override
  @PutMapping(path = "/{tripId}/planner-conditions", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> replace(@PathVariable String tripId, HttpServletRequest request) {
    TripPreferencesRequestBoundary.requireNoQuery(request);
    TripPreferencesRequestBoundary.requireJsonMediaType(request);
    UUID id;
    try {
      id = UUID.fromString(tripId);
      if (!id.toString().equals(tripId)) throw TripException.invalidRequest();
    } catch (IllegalArgumentException failure) {
      throw TripException.invalidRequest();
    }
    long revision = TripPreferencesRequestBoundary.requiredRevision(request, id);
    var conditions = codec.decode(TripPreferencesRequestBoundary.readRequiredBody(request));
    var user = users.getRequired();
    String key = TripPreferencesRequestBoundary.requiredSingleHeader(request, "Idempotency-Key");
    var command =
        IdempotencyRequest.create(
            user.userId(),
            "PUT",
            "/api/v1/trips/" + id + "/planner-conditions",
            key,
            mapper.writeValueAsBytes(conditions));
    var replayed = new AtomicBoolean(true);
    var result =
        receipts.execute(
            command,
            () -> {
              replayed.set(false);
              var saved = store.replace(user, id, revision, conditions);
              var body = mapper.createObjectNode();
              body.put("tripId", id.toString());
              body.set("plannerConditions", mapper.valueToTree(saved.payload()));
              body.put("scheduleEffect", saved.scheduleEffect());
              body.put("regenerationRequired", saved.regenerationRequired());
              if (saved.activeScheduleVersionId() == null) body.putNull("activeScheduleVersionId");
              else body.put("activeScheduleVersionId", saved.activeScheduleVersionId().toString());
              return new IdempotencyResponse(
                  200,
                  List.of(
                      new IdempotencyHeader(
                          HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE),
                      new IdempotencyHeader(HttpHeaders.ETAG, saved.etag())),
                  mapper.writeValueAsBytes(body));
            });
    var response = ResponseEntity.status(result.status());
    result.headers().forEach(header -> response.header(header.name(), header.value()));
    return response
        .header("Idempotency-Replayed", Boolean.toString(replayed.get()))
        .body(result.body());
  }
}
