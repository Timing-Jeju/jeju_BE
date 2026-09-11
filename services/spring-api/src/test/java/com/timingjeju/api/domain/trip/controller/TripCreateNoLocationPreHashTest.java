package com.timingjeju.api.domain.trip.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.timingjeju.api.application.idempotency.*;
import com.timingjeju.api.application.security.*;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.service.TripService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import tools.jackson.databind.ObjectMapper;

// #224 사전 JDK/JUnit 검증. 선행 base 반영 뒤 정규 Gradle/HTTP 검증이 별도로 필요하다.
@Tag("unit")
class TripCreateNoLocationPreHashTest {
  private static final UUID OWNER = UUID.fromString("44000000-0000-0000-0000-000000000001");
  private static final String KEY = "44000000-0000-0000-0000-000000000044";
  private final TripService trips = mock(TripService.class);
  private final CurrentUserAccessor users = mock(CurrentUserAccessor.class);
  private final IdempotencyUseCase idempotency = mock(IdempotencyUseCase.class);

  private TripController controller() {
    when(users.getRequired())
        .thenReturn(new CurrentUser(OWNER, AuthenticatedRole.AUTHENTICATED, null));
    return new TripController(
        trips,
        users,
        idempotency,
        new ObjectMapper(),
        org.mockito.Mockito.mock(
            com.timingjeju.api.application.trip.service.TripDayActivityWindowService.class));
  }

  private static final String VALID_BODY =
      """
      {"title":"제주","startDate":"2026-08-03","endDate":"2026-08-05",
       "transportModes":[{"mode":"public_transit","priority":1,"primary":true}]}
      """
          .strip();

  private static java.util.stream.Stream<String> rejectedBodies() {
    String prefix = VALID_BODY.substring(0, VALID_BODY.length() - 1);
    return java.util.stream.Stream.of(
        prefix + ",\"currentLocation\":{\"latitude\":0,\"longitude\":0}}",
        prefix + ",\"geo_hash\":\"synthetic-derived-value\"}",
        VALID_BODY.replace(
            "\"priority\":1", "\"priority\":1,\"nearestPlaceId\":\"synthetic-derived-value\""),
        prefix + ",\"title\":\"second\"}",
        VALID_BODY + " {}",
        "null",
        "[]",
        "",
        VALID_BODY + " ".repeat(IdempotencyRequest.MAX_BODY_BYTES),
        "{");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("rejectedBodies")
  void 거부할_입력은_멱등성_hash_생성과_저장_전에_차단한다(String body) {
    var controller = controller();
    when(idempotency.execute(any(), any()))
        .thenAnswer(invocation -> invocation.<IdempotencyOperation>getArgument(1).execute());
    try (var hashes = mockStatic(IdempotencyRequest.class, CALLS_REAL_METHODS)) {
      assertThatThrownBy(() -> controller.create(KEY, body.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(TripException.class);
      hashes.verifyNoInteractions();
    }
    verifyNoInteractions(idempotency, trips);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void 정상_재시도는_크기_상한까지_원문_byte_hash와_기존_응답을_유지한다(boolean atLimit) {
    var controller = controller();
    byte[] body = VALID_BODY.getBytes(StandardCharsets.UTF_8);
    if (atLimit) {
      int originalLength = body.length;
      body = java.util.Arrays.copyOf(body, IdempotencyRequest.MAX_BODY_BYTES);
      java.util.Arrays.fill(body, originalLength, body.length, (byte) ' ');
    }
    final byte[] expectedBody = body;
    var expected = IdempotencyRequest.create(OWNER, "POST", "/api/v1/trips", KEY, body);
    when(idempotency.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              IdempotencyRequest request = invocation.getArgument(0);
              assertThat(request.body()).containsExactly(expectedBody);
              assertThat(request.requestHash()).isEqualTo(expected.requestHash());
              return new IdempotencyResponse(
                  201, List.of(new IdempotencyHeader("ETag", "saved-etag")), new byte[] {123, 125});
            });
    var response = controller.create(KEY, body);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    assertThat(response.getHeaders().getFirst("ETag")).isEqualTo("saved-etag");
    verifyNoInteractions(trips);
  }
}
