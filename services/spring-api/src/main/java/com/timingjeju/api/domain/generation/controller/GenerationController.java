package com.timingjeju.api.domain.generation.controller;

import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.application.idempotency.*;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/trips")
public class GenerationController
    implements com.timingjeju.api.domain.generation.controller.docs.GenerationApiDocs {
  private final com.timingjeju.api.application.generation.service.GenerationIntakeService intake;
  private final CurrentUserAccessor users;
  private final IdempotencyUseCase receipts;
  private final ObjectMapper mapper;

  public GenerationController(
      com.timingjeju.api.application.generation.service.GenerationIntakeService intake,
      CurrentUserAccessor users,
      IdempotencyUseCase receipts,
      ObjectMapper mapper) {
    this.intake = intake;
    this.users = users;
    this.receipts = receipts;
    this.mapper = mapper;
  }

  @PostMapping(path = "/{tripId}/schedule-generations", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> create(@PathVariable String tripId, HttpServletRequest request) {
    UUID id;
    try {
      id = UUID.fromString(tripId);
      if (!id.toString().equals(tripId)) throw GenerationException.invalidPath();
    } catch (IllegalArgumentException failure) {
      throw GenerationException.invalidPath();
    }
    var command = new GenerationRequestCodec(mapper).decode(GenerationHttpBoundary.body(request));
    long revision;
    try {
      var tag = TripEntityTag.parse(GenerationHttpBoundary.header(request, "If-Match"));
      if (!id.equals(tag.tripId())) throw GenerationException.ifMatchInvalid();
      revision = tag.revision();
    } catch (TripException failure) {
      throw GenerationException.ifMatchInvalid();
    }
    var user = users.getRequired();
    var receipt =
        GenerationHttpBoundary.receipt(
            user.userId(),
            "/api/v1/trips/" + id + "/schedule-generations",
            GenerationHttpBoundary.header(request, "Idempotency-Key"),
            mapper.writeValueAsBytes(command));
    intake.requireOwned(user.userId(), id);
    var replayed = new AtomicBoolean(true);
    var result =
        receipts.execute(
            receipt,
            () -> {
              replayed.set(false);
              var accepted = intake.accept(user.userId(), id, revision, command);
              return new IdempotencyResponse(
                  202,
                  List.of(
                      new IdempotencyHeader("Content-Type", "application/json"),
                      new IdempotencyHeader("Location", accepted.pollUrl()),
                      new IdempotencyHeader("Retry-After", "2")),
                  mapper.writeValueAsBytes(
                      com.timingjeju.api.domain.generation.dto.GenerationAcceptedResponse.from(
                          accepted)));
            });
    var response = ResponseEntity.status(result.status());
    result.headers().forEach(h -> response.header(h.name(), h.value()));
    return response
        .header("Idempotency-Replayed", Boolean.toString(replayed.get()))
        .body(result.body());
  }
}
