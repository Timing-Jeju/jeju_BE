package com.timingjeju.api.domain.generation.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.application.idempotency.*;
import com.timingjeju.api.application.security.*;
import com.timingjeju.api.application.trip.TripEntityTag;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class GenerationControllerTest {
  @Test
  void 실제_MVC_경로는_필수_ETag_오류를_8필드_Problem으로_반환한다() throws Exception {
    var mapper = JsonMapper.builder().build();
    var store = mock(GenerationIntakeStore.class);
    var controller =
        new GenerationController(
            new com.timingjeju.api.application.generation.service.GenerationIntakeService(
                store, Clock.systemUTC()),
            () ->
                Optional.of(
                    new CurrentUser(UUID.randomUUID(), AuthenticatedRole.AUTHENTICATED, null)),
            mock(IdempotencyUseCase.class),
            mapper);
    var writer =
        new com.timingjeju.api.global.error.ProblemResponseWriter(
            mapper,
            new com.timingjeju.api.global.error.ProblemCodeRegistry(List.of()),
            new com.timingjeju.api.global.logging.RequestTraceId(() -> "a".repeat(32)));
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GenerationProblemExceptionHandler(writer))
            .build();
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/trips/" + UUID.randomUUID() + "/schedule-generations")
                    .contentType("application/json")
                    .header("Idempotency-Key", "retry")
                    .content(
                        mapper.writeValueAsBytes(
                            new CreateGenerationCommand(UUID.randomUUID(), null, 3))))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isBadRequest())
            .andReturn()
            .getResponse();
    var problem = mapper.readTree(response.getContentAsByteArray());
    assertThat(problem.size()).isEqualTo(8);
    assertThat(problem.get("code").asText()).isEqualTo("IF_MATCH_REQUIRED");
    assertThat(problem.get("fieldErrors").size()).isEqualTo(1);
    assertThat(problem.get("fieldErrors").get(0).get("detail").asText()).isEqualTo("필수 헤더입니다.");
    assertThat(response.getContentType()).startsWith("application/problem+json");
    verifyNoInteractions(store);
  }

  @Test
  void 접수는_필수헤더와_printable_멱등키를_검증하고_202와_polling헤더를_반환한다() {
    var owner = UUID.randomUUID();
    var trip = UUID.randomUUID();
    var day = UUID.randomUUID();
    var run = UUID.randomUUID();
    var store = mock(GenerationIntakeStore.class);
    var now = Instant.parse("2026-09-13T12:00:00Z");
    var accepted =
        new GenerationAccepted(
            "1.0.0",
            run,
            "queued",
            "/api/v1/trips/" + trip + "/schedule-generations/" + run,
            "a".repeat(64),
            now.atOffset(ZoneOffset.ofHours(9)));
    when(store.accept(eq(owner), eq(trip), eq(1L), any(), eq(now))).thenReturn(accepted);
    var receipts = mock(IdempotencyUseCase.class);
    when(receipts.execute(any(), any()))
        .thenAnswer(call -> call.<IdempotencyOperation>getArgument(1).execute());
    var controller =
        new GenerationController(
            new com.timingjeju.api.application.generation.service.GenerationIntakeService(
                store, Clock.fixed(now, ZoneOffset.UTC)),
            () -> Optional.of(new CurrentUser(owner, AuthenticatedRole.AUTHENTICATED, null)),
            receipts,
            JsonMapper.builder().build());
    var request = new MockHttpServletRequest();
    request.setContentType("application/json");
    request.setContent(
        ("{\"targetDayId\":\""
                + day
                + "\",\"expectedActiveScheduleVersionId\":null,\"candidateCount\":3}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    request.addHeader("If-Match", TripEntityTag.strong(trip, 1));
    request.addHeader("Idempotency-Key", "retry key, 1");
    var response = controller.create(trip.toString(), request);
    assertThat(response.getStatusCode().value()).isEqualTo(202);
    assertThat(response.getHeaders().getFirst("Location")).isEqualTo(accepted.pollUrl());
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
    assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
    assertThat(JsonMapper.builder().build().readTree(response.getBody()).get("acceptedAt").asText())
        .endsWith("+09:00");
    request.removeHeader("If-Match");
    assertThatThrownBy(() -> controller.create(trip.toString(), request))
        .isInstanceOf(GenerationException.class);
    verify(store, times(1)).accept(any(), any(), anyLong(), any(), any());
  }
}
