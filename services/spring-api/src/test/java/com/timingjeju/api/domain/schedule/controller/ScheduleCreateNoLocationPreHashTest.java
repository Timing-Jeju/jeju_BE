package com.timingjeju.api.domain.schedule.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.idempotency.IdempotencyOperation;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.schedule.service.ScheduleMutationService;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.TripEntityTag;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class ScheduleCreateNoLocationPreHashTest {
  private static final String VALID_BODY =
      """
      {"expectedActiveScheduleVersionId":"46000000-0000-0000-0000-000000000001",
       "dayNo":1,"sequenceNo":1,"itemType":"place_visit",
       "placeId":"34000000-0000-0000-0000-000000000001",
       "plannedStartAt":"2026-09-01T09:00:00+09:00","stayMinutes":30}
      """
          .strip();

  private static java.util.stream.Stream<String> rejectedBodies() {
    String prefix = VALID_BODY.substring(0, VALID_BODY.length() - 1);
    return java.util.stream.Stream.of(
        prefix + ",\"currentLocation\":{\"latitude\":0,\"longitude\":0}}",
        prefix + ",\"geo_hash\":\"synthetic-derived-value\"}",
        prefix + ",\"dayNo\":2}",
        VALID_BODY + " {}",
        "null",
        "[]",
        "",
        VALID_BODY + " ".repeat(IdempotencyRequest.MAX_BODY_BYTES),
        "{");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("rejectedBodies")
  void 일정_추가의_거부할_입력은_멱등성_hash와_저장_전에_차단한다(String raw) {
    UUID owner = UUID.fromString("44000000-0000-0000-0000-000000000001");
    UUID trip = UUID.fromString("45000000-0000-0000-0000-000000000001");
    var users = mock(CurrentUserAccessor.class);
    when(users.getRequired())
        .thenReturn(new CurrentUser(owner, AuthenticatedRole.AUTHENTICATED, null));
    var schedules = mock(ScheduleMutationService.class);
    var idempotency = mock(IdempotencyUseCase.class);
    var canonicalizer = mock(CommandInputCanonicalizer.class);
    when(idempotency.execute(any(), any()))
        .thenAnswer(invocation -> invocation.<IdempotencyOperation>getArgument(1).execute());
    var controller =
        new ScheduleMutationController(
            schedules, users, idempotency, new ObjectMapper(), canonicalizer);
    byte[] body = raw.getBytes(StandardCharsets.UTF_8);
    var http = new MockHttpServletRequest();
    http.setContent(body);
    http.addHeader("Content-Length", Integer.toString(body.length));
    try (var hashes = mockStatic(IdempotencyRequest.class, CALLS_REAL_METHODS)) {
      assertThatThrownBy(
              () ->
                  controller.addItem(
                      trip.toString(),
                      TripEntityTag.strong(trip, 1),
                      "44000000-0000-0000-0000-000000000044",
                      body,
                      http))
          .isInstanceOf(ScheduleException.class);
      hashes.verifyNoInteractions();
    }
    verifyNoInteractions(idempotency, schedules, canonicalizer);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void 정상_일정_추가는_크기_상한까지_원문_hash와_멱등_재시도_응답을_보존한다(boolean atLimit) {
    UUID owner = UUID.fromString("44000000-0000-0000-0000-000000000001");
    UUID trip = UUID.fromString("45000000-0000-0000-0000-000000000001");
    var users = mock(CurrentUserAccessor.class);
    when(users.getRequired())
        .thenReturn(new CurrentUser(owner, AuthenticatedRole.AUTHENTICATED, null));
    var schedules = mock(ScheduleMutationService.class);
    var idempotency = mock(IdempotencyUseCase.class);
    var controller =
        new ScheduleMutationController(
            schedules,
            users,
            idempotency,
            new ObjectMapper(),
            mock(CommandInputCanonicalizer.class));
    byte[] body = VALID_BODY.getBytes(StandardCharsets.UTF_8);
    if (atLimit) {
      int originalLength = body.length;
      body = java.util.Arrays.copyOf(body, IdempotencyRequest.MAX_BODY_BYTES);
      java.util.Arrays.fill(body, originalLength, body.length, (byte) ' ');
    }
    final byte[] expectedBody = body;
    String key = "44000000-0000-0000-0000-000000000044";
    String path = "/api/v1/trips/" + trip + "/schedule-items";
    var expected = IdempotencyRequest.create(owner, "POST", path, key, body);
    when(idempotency.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              IdempotencyRequest actual = invocation.getArgument(0);
              org.assertj.core.api.Assertions.assertThat(actual.body())
                  .containsExactly(expectedBody);
              org.assertj.core.api.Assertions.assertThat(actual.requestHash())
                  .isEqualTo(expected.requestHash());
              return new com.timingjeju.api.application.idempotency.IdempotencyResponse(
                  201, java.util.List.of(), new byte[] {123, 125});
            });
    var http = new MockHttpServletRequest();
    http.setContent(body);
    http.addHeader("Content-Length", Integer.toString(body.length));
    var response =
        controller.addItem(trip.toString(), TripEntityTag.strong(trip, 1), key, body, http);
    org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(201);
    org.assertj.core.api.Assertions.assertThat(
            response.getHeaders().getFirst("Idempotency-Replayed"))
        .isEqualTo("true");
    verifyNoInteractions(schedules);
  }
}
