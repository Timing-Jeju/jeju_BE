package com.timingjeju.api.domain.generation.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.application.generation.service.GenerationApplyService;
import com.timingjeju.api.application.idempotency.*;
import com.timingjeju.api.application.security.*;
import com.timingjeju.api.application.trip.TripEntityTag;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class GenerationApplyControllerTest {
  private final UUID owner = UUID.randomUUID(),
      trip = UUID.randomUUID(),
      run = UUID.randomUUID(),
      candidate = UUID.randomUUID(),
      version = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-09-13T00:00:00Z");
  private final GenerationApplyStore store = mock(GenerationApplyStore.class);
  private final IdempotencyUseCase receipts = mock(IdempotencyUseCase.class);
  private final JsonMapper mapper = JsonMapper.builder().build();

  private GenerationApplyController controller() {
    return new GenerationApplyController(
        new GenerationApplyService(store, Clock.fixed(now, ZoneOffset.UTC)),
        () -> Optional.of(new CurrentUser(owner, AuthenticatedRole.AUTHENTICATED, null)),
        receipts,
        mapper);
  }

  @Test
  void 최초_null_기준으로_적용하고_응답유실_재시도는_동일_200_ETag_본문을_재생한다() {
    when(store.apply(owner, trip, run, candidate, 1, null, now))
        .thenReturn(new GenerationApplyStore.Applied(trip, run, candidate, null, version, 2, now));
    var retained = new AtomicReference<IdempotencyResponse>();
    when(receipts.execute(any(), any()))
        .thenAnswer(
            call -> {
              if (retained.get() == null)
                retained.set(call.<IdempotencyOperation>getArgument(1).execute());
              return retained.get();
            });
    var controller = controller();
    var first =
        controller.apply(
            trip.toString(),
            run.toString(),
            candidate.toString(),
            request("{\"expectedActiveScheduleVersionId\":null}"));
    var retry =
        controller.apply(
            trip.toString(),
            run.toString(),
            candidate.toString(),
            request("{\"expectedActiveScheduleVersionId\":null}"));
    assertThat(first.getStatusCode().value()).isEqualTo(200);
    assertThat(retry.getBody()).isEqualTo(first.getBody());
    assertThat(first.getHeaders().getFirst("ETag")).isEqualTo(TripEntityTag.strong(trip, 2));
    assertThat(retry.getHeaders().getFirst("ETag")).isEqualTo(first.getHeaders().getFirst("ETag"));
    assertThat(first.getHeaders().getFirst("Location"))
        .isEqualTo("/api/v1/trips/" + trip + "/schedule-versions/" + version);
    assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
    assertThat(retry.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    var json = mapper.readTree(first.getBody());
    assertThat(json.get("previousScheduleVersionId").isNull()).isTrue();
    assertThat(json.get("activeScheduleVersionId").asText()).isEqualTo(version.toString());
    verify(store, times(1)).apply(owner, trip, run, candidate, 1, null, now);
    verify(store, times(2)).requireOwned(owner, trip, run, candidate);
  }

  @Test
  void 누락_추가_중복_필드와_비정규_UUID는_쓰기나_멱등접수_없이_거부한다() {
    var controller = controller();
    for (String body :
        List.of(
            "{}",
            "null",
            "{\"expectedActiveScheduleVersionId\":null,\"extra\":1}",
            "{\"expectedActiveScheduleVersionId\":null,\"expectedActiveScheduleVersionId\":null}",
            "{\"expectedActiveScheduleVersionId\":\"1-1-1-1-1\"}",
            "{\"expectedActiveScheduleVersionId\":0}")) {
      assertThatThrownBy(
              () ->
                  controller.apply(
                      trip.toString(), run.toString(), candidate.toString(), request(body)))
          .hasMessage("INVALID_ASYNC_RUN_REQUEST");
    }
    var noTag = request("{\"expectedActiveScheduleVersionId\":null}");
    noTag.removeHeader("If-Match");
    assertThatThrownBy(
            () -> controller.apply(trip.toString(), run.toString(), candidate.toString(), noTag))
        .hasMessage("IF_MATCH_REQUIRED");
    verifyNoInteractions(store, receipts);
  }

  @Test
  void 소유권_철회는_기존_멱등응답도_재생하지_않는다() {
    doThrow(com.timingjeju.api.application.trip.TripException.notFound())
        .when(store)
        .requireOwned(owner, trip, run, candidate);
    assertThatThrownBy(
            () ->
                controller()
                    .apply(
                        trip.toString(),
                        run.toString(),
                        candidate.toString(),
                        request("{\"expectedActiveScheduleVersionId\":null}")))
        .hasMessage("TRIP_NOT_FOUND");
    verifyNoInteractions(receipts);
    verify(store, never()).apply(any(), any(), any(), any(), anyLong(), any(), any());
  }

  @Test
  void 적용_도메인오류는_MVC에서_정해진_Problem_상태와_코드로_반환한다() throws Exception {
    var writer =
        new com.timingjeju.api.global.error.ProblemResponseWriter(
            mapper,
            new com.timingjeju.api.global.error.ProblemCodeRegistry(List.of()),
            new com.timingjeju.api.global.logging.RequestTraceId(() -> "a".repeat(32)));
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller())
            .setControllerAdvice(new GenerationProblemExceptionHandler(writer))
            .build();
    when(receipts.execute(any(), any()))
        .thenAnswer(call -> call.<IdempotencyOperation>getArgument(1).execute());
    var cases =
        Map.of(
            GenerationException.candidateNotFound(),
            404,
            GenerationException.candidateAlreadyApplied(),
            409,
            GenerationException.candidateStale(),
            409,
            GenerationException.candidateExpired(),
            410,
            GenerationException.candidateEvidenceUnavailable(),
            410,
            GenerationException.candidateNotApplicable(),
            422,
            GenerationException.resultUnavailable(),
            503);
    for (var entry : cases.entrySet()) {
      doThrow(entry.getKey()).when(store).apply(owner, trip, run, candidate, 1, null, now);
      var response =
          mvc.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                          "/api/v1/trips/"
                              + trip
                              + "/schedule-generations/"
                              + run
                              + "/candidates/"
                              + candidate
                              + "/apply")
                      .contentType("application/json")
                      .content("{\"expectedActiveScheduleVersionId\":null}")
                      .header("If-Match", TripEntityTag.strong(trip, 1))
                      .header("Idempotency-Key", "retry-key"))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).isEqualTo(entry.getValue());
      var problem = mapper.readTree(response.getContentAsByteArray());
      assertThat(problem.size()).isEqualTo(8);
      assertThat(problem.get("code").asText()).isEqualTo(entry.getKey().code());
    }
  }

  private MockHttpServletRequest request(String body) {
    var request = new MockHttpServletRequest();
    request.setContentType("application/json");
    request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    request.addHeader("If-Match", TripEntityTag.strong(trip, 1));
    request.addHeader("Idempotency-Key", "same apply retry key");
    return request;
  }
}
