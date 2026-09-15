package com.timingjeju.api.domain.generation.controller;

import com.timingjeju.api.application.generation.GenerationException;
import com.timingjeju.api.application.generation.service.GenerationApplyService;
import com.timingjeju.api.application.idempotency.*;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.*;
import com.timingjeju.api.domain.generation.dto.GenerationAppliedResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/trips")
public class GenerationApplyController
    implements com.timingjeju.api.domain.generation.controller.docs.GenerationApplyApiDocs {
  private final GenerationApplyService applications;
  private final CurrentUserAccessor users;
  private final IdempotencyUseCase receipts;
  private final ObjectMapper mapper;

  public GenerationApplyController(
      GenerationApplyService applications,
      CurrentUserAccessor users,
      IdempotencyUseCase receipts,
      ObjectMapper mapper) {
    this.applications = applications;
    this.users = users;
    this.receipts = receipts;
    this.mapper = mapper;
  }

  @PostMapping(
      path = "/{tripId}/schedule-generations/{runId}/candidates/{candidateId}/apply",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> apply(
      @PathVariable String tripId,
      @PathVariable String runId,
      @PathVariable String candidateId,
      HttpServletRequest request) {
    var trip = id(tripId);
    var run = id(runId);
    var candidate = id(candidateId);
    var codec = new GenerationRequestCodec(mapper);
    var expectedActive = codec.decodeApply(GenerationHttpBoundary.body(request));
    var owner = users.getRequired().userId();
    var receipt =
        GenerationHttpBoundary.receipt(
            owner,
            "/api/v1/trips/"
                + trip
                + "/schedule-generations/"
                + run
                + "/candidates/"
                + candidate
                + "/apply",
            GenerationHttpBoundary.header(request, "Idempotency-Key"),
            codec.canonicalApplyBody(expectedActive));
    long revision;
    try {
      var tag = TripEntityTag.parse(GenerationHttpBoundary.header(request, "If-Match"));
      if (!trip.equals(tag.tripId())) throw GenerationException.ifMatchInvalid();
      revision = tag.revision();
    } catch (TripException failure) {
      throw GenerationException.ifMatchInvalid();
    }
    // Replay 전에도 현재 소유권을 확인하되 만료·ETag·선택 여부는 재평가하지 않는다.
    applications.requireOwned(owner, trip, run, candidate);
    var replayed = new AtomicBoolean(true);
    var result =
        receipts.execute(
            receipt,
            () -> {
              replayed.set(false);
              var applied =
                  applications.apply(owner, trip, run, candidate, revision, expectedActive);
              return new IdempotencyResponse(
                  200,
                  List.of(
                      new IdempotencyHeader("Content-Type", "application/json"),
                      new IdempotencyHeader(
                          "ETag", TripEntityTag.strong(trip, applied.tripRevision())),
                      new IdempotencyHeader(
                          "Location",
                          "/api/v1/trips/"
                              + trip
                              + "/schedule-versions/"
                              + applied.activeScheduleVersionId())),
                  mapper.writeValueAsBytes(GenerationAppliedResponse.from(applied)));
            });
    var response = ResponseEntity.status(result.status());
    result.headers().forEach(header -> response.header(header.name(), header.value()));
    return response
        .header("Idempotency-Replayed", Boolean.toString(replayed.get()))
        .body(result.body());
  }

  private static UUID id(String value) {
    try {
      var id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw GenerationException.invalidPath();
      return id;
    } catch (IllegalArgumentException failure) {
      throw GenerationException.invalidPath();
    }
  }
}
