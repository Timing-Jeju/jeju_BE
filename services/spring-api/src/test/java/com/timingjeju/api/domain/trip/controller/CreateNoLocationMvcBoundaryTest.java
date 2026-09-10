package com.timingjeju.api.domain.trip.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.idempotency.IdempotencyHeader;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyResponse;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.schedule.service.ScheduleMutationService;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.service.TripService;
import com.timingjeju.api.domain.schedule.controller.ScheduleMutationController;
import com.timingjeju.api.domain.schedule.controller.ScheduleMutationProblemExceptionHandler;
import com.timingjeju.api.domain.trip.exception.TripProblemDefinitions;
import com.timingjeju.api.global.error.ProblemCodeRegistry;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import com.timingjeju.api.global.logging.RequestTraceId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/** 실제 Controller/Advice의 MVC 거부 경계를 검사하며 인증 필터와 DB 통합은 별도 검증한다. */
@Tag("unit")
class CreateNoLocationMvcBoundaryTest {
  private static String validBody(boolean schedule) {
    return schedule
        ? """
        {"expectedActiveScheduleVersionId":"46000000-0000-0000-0000-000000000001",
         "dayNo":1,"sequenceNo":1,"itemType":"place_visit",
         "placeId":"34000000-0000-0000-0000-000000000001",
         "plannedStartAt":"2026-09-01T09:00:00+09:00","stayMinutes":30}
        """
            .strip()
        : """
        {"title":"제주","startDate":"2026-08-03","endDate":"2026-08-05",
         "transportModes":[{"mode":"public_transit","priority":1,"primary":true}]}
        """
            .strip();
  }

  private static Stream<Arguments> requests() {
    return Stream.of(false, true)
        .flatMap(
            schedule -> {
              String valid = validBody(schedule);
              String withLocation =
                  valid.substring(0, valid.length() - 1)
                      + ",\"currentLocation\":{\"latitude\":0,\"longitude\":0}}";
              return Stream.of(
                  Arguments.of(schedule, "null", false),
                  Arguments.of(schedule, "[]", false),
                  Arguments.of(schedule, withLocation, false),
                  Arguments.of(schedule, valid, true));
            });
  }

  @ParameterizedTest(name = "요청 경계 {index}")
  @MethodSource("requests")
  void MVC는_거부_입력의_400과_정상_재시도의_201을_구분한다(boolean schedule, String body, boolean accepted)
      throws Exception {
    var mapper = new ObjectMapper();
    var users = mock(CurrentUserAccessor.class);
    var owner = UUID.fromString("44000000-0000-0000-0000-000000000001");
    var trip = UUID.fromString("45000000-0000-0000-0000-000000000001");
    when(users.getRequired())
        .thenReturn(new CurrentUser(owner, AuthenticatedRole.AUTHENTICATED, null));
    var trips = mock(TripService.class);
    var schedules = mock(ScheduleMutationService.class);
    var idempotency = mock(IdempotencyUseCase.class);
    var canonicalizer = mock(CommandInputCanonicalizer.class);
    var writer =
        new ProblemResponseWriter(
            mapper,
            new ProblemCodeRegistry(List.of(new TripProblemDefinitions())),
            new RequestTraceId(() -> "44000000000000000000000000000001"));
    Object controller =
        schedule
            ? new ScheduleMutationController(schedules, users, idempotency, mapper, canonicalizer)
            : new TripController(trips, users, idempotency, mapper);
    Object advice =
        schedule
            ? new ScheduleMutationProblemExceptionHandler(writer)
            : new TripProblemExceptionHandler(writer, mock(TripPreferencesProblemWriter.class));
    var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
    String path = schedule ? "/api/v1/trips/" + trip + "/schedule-items" : "/api/v1/trips";
    String key = "44000000-0000-0000-0000-000000000044";
    if (accepted) {
      byte[] raw = body.getBytes(StandardCharsets.UTF_8);
      var expected = IdempotencyRequest.create(owner, "POST", path, key, raw);
      when(idempotency.execute(any(), any()))
          .thenAnswer(
              invocation -> {
                IdempotencyRequest request = invocation.getArgument(0);
                assertThat(request.body()).containsExactly(raw);
                assertThat(request.requestHash()).isEqualTo(expected.requestHash());
                return new IdempotencyResponse(
                    201,
                    List.of(new IdempotencyHeader("ETag", "saved-etag")),
                    "{}".getBytes(StandardCharsets.UTF_8));
              });
    }
    try (var hashes = mockStatic(IdempotencyRequest.class, CALLS_REAL_METHODS)) {
      var response =
          mvc.perform(
                  post(path)
                      .contentType("application/json")
                      .header("If-Match", TripEntityTag.strong(trip, 1))
                      .header("Idempotency-Key", key)
                      .content(body))
              .andReturn()
              .getResponse();
      if (accepted) {
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(response.getHeader("ETag")).isEqualTo("saved-etag");
        assertThat(response.getContentAsString()).isEqualTo("{}");
        verify(idempotency).execute(any(), any());
      } else {
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        var problem = mapper.readTree(response.getContentAsByteArray());
        assertThat(problem.path("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getContentAsString()).doesNotContain("currentLocation", "latitude");
        hashes.verifyNoInteractions();
        verifyNoInteractions(idempotency);
      }
    }
    verifyNoInteractions(trips, schedules, canonicalizer);
  }
}
