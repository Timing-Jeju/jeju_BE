package com.timingjeju.api.domain.generation.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.application.generation.service.GenerationQueryService;
import com.timingjeju.api.application.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class GenerationQueryControllerTest {
  private final UUID owner = UUID.randomUUID(), trip = UUID.randomUUID(), run = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-09-13T00:00:00Z");
  private final GenerationRunReader reader = mock(GenerationRunReader.class);
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final String path = "/api/v1/trips/" + trip + "/schedule-generations/" + run;

  private org.springframework.test.web.servlet.MockMvc mvc() {
    var controller =
        new GenerationQueryController(
            new GenerationQueryService(reader, Clock.fixed(now, ZoneOffset.UTC)),
            () -> Optional.of(new CurrentUser(owner, AuthenticatedRole.AUTHENTICATED, null)));
    var writer =
        new com.timingjeju.api.global.error.ProblemResponseWriter(
            mapper,
            new com.timingjeju.api.global.error.ProblemCodeRegistry(List.of()),
            new com.timingjeju.api.global.logging.RequestTraceId(() -> "a".repeat(32)));
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GenerationProblemExceptionHandler(writer))
        .build();
  }

  @Test
  void 대기실행만_재조회헤더를_주고_종료결과는_저장된_응답으로_반환한다() throws Exception {
    var mvc = mvc();
    for (String status : List.of("queued", "running", "succeeded", "failed", "cancelled")) {
      when(reader.findOwned(owner, trip, run))
          .thenReturn(Optional.of(saved(status, now.plus(Duration.ofDays(7)))));
      var response = mvc.perform(get(path)).andReturn().getResponse();
      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getHeader("Retry-After"))
          .isEqualTo(Set.of("queued", "running").contains(status) ? "2" : null);
      assertThat(response.getHeader("Cache-Control")).contains("no-store");
      var json = mapper.readTree(response.getContentAsByteArray());
      assertThat(json.get("status").asText()).isEqualTo(status);
      assertThat(json.get("pollUrl").asText()).isEqualTo(path);
      assertThat(json.has("mcpInputHash")).isFalse();
    }
  }

  @Test
  void 조회는_query와_모든본문을_거부하고_저장소를_읽지_않는다() throws Exception {
    var mvc = mvc();
    var query = mvc.perform(get(path + "?unexpected=1")).andReturn().getResponse();
    assertThat(query.getStatus()).isEqualTo(400);
    assertThat(mapper.readTree(query.getContentAsByteArray()).get("code").asText())
        .isEqualTo("INVALID_QUERY_PARAMETER");
    for (String body : List.of("{}", "null", " ")) {
      var response =
          mvc.perform(get(path).contentType("application/json").content(body))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(mapper.readTree(response.getContentAsByteArray()).get("code").asText())
          .isEqualTo("REQUEST_BODY_NOT_ALLOWED");
    }
    verifyNoInteractions(reader);
  }

  @Test
  void 비소유없는작업은_404이고_소유한_7일만료는_410이다() throws Exception {
    var mvc = mvc();
    when(reader.findOwned(owner, trip, run)).thenReturn(Optional.empty());
    var missing = mvc.perform(get(path)).andReturn().getResponse();
    assertThat(missing.getStatus()).isEqualTo(404);
    assertThat(mapper.readTree(missing.getContentAsByteArray()).get("code").asText())
        .isEqualTo("ASYNC_RUN_NOT_FOUND");
    when(reader.findOwned(owner, trip, run)).thenReturn(Optional.of(saved("failed", now)));
    var expired = mvc.perform(get(path)).andReturn().getResponse();
    assertThat(expired.getStatus()).isEqualTo(410);
    assertThat(mapper.readTree(expired.getContentAsByteArray()).get("code").asText())
        .isEqualTo("ASYNC_RESULT_EXPIRED");
  }

  private GenerationRunReader.SavedRun saved(String status, Instant expiry) {
    boolean terminal = Set.of("succeeded", "failed", "cancelled").contains(status);
    var completed = expiry.minus(Duration.ofDays(7));
    return new GenerationRunReader.SavedRun(
        run,
        trip,
        UUID.randomUUID(),
        null,
        status,
        status.equals("succeeded") ? "insufficient_feasible_routes" : null,
        "a".repeat(64),
        completed,
        status.equals("queued") ? null : completed,
        terminal ? completed : null,
        terminal ? expiry : null,
        status.equals("succeeded") ? completed : null,
        false,
        GenerationFailure.from(status, "MCP_TIMEOUT"),
        List.of());
  }
}
