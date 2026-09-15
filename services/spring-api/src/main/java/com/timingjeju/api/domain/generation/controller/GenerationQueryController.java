package com.timingjeju.api.domain.generation.controller;

import com.timingjeju.api.application.generation.GenerationException;
import com.timingjeju.api.application.generation.service.GenerationQueryService;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.domain.generation.dto.GenerationRunResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trips")
public class GenerationQueryController
    implements com.timingjeju.api.domain.generation.controller.docs.GenerationQueryApiDocs {
  private final GenerationQueryService queries;
  private final CurrentUserAccessor users;

  public GenerationQueryController(GenerationQueryService queries, CurrentUserAccessor users) {
    this.queries = queries;
    this.users = users;
  }

  @GetMapping(
      path = "/{tripId}/schedule-generations/{runId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<GenerationRunResponse> read(
      @PathVariable String tripId, @PathVariable String runId, HttpServletRequest request) {
    var trip = id(tripId);
    var run = id(runId);
    if (request.getQueryString() != null || !request.getParameterMap().isEmpty())
      throw GenerationException.invalidQuery();
    try {
      if (request.getContentLengthLong() > 0
          || request.getHeader("Transfer-Encoding") != null
          || request.getInputStream().read() != -1) throw GenerationException.bodyForbidden();
    } catch (IOException failure) {
      throw GenerationException.bodyForbidden();
    }
    var saved = queries.read(users.getRequired().userId(), trip, run);
    var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
    if (Set.of("queued", "running").contains(saved.status())) response.header("Retry-After", "2");
    return response.body(GenerationRunResponse.from(saved));
  }

  private static UUID id(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw GenerationException.invalidPath();
      return id;
    } catch (IllegalArgumentException failure) {
      throw GenerationException.invalidPath();
    }
  }
}
