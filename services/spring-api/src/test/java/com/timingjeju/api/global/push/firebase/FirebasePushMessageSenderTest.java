package com.timingjeju.api.global.push.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.push.PushErrorClass;
import com.timingjeju.api.application.push.PushMessage;
import com.timingjeju.api.application.push.PushPlatformHints;
import com.timingjeju.api.application.push.PushSendResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FirebasePushMessageSenderTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-26T04:00:00Z"), ZoneOffset.UTC);

  @Test
  void provider_message_id는_ACCEPTED_접수_증거일뿐_DELIVERED를_표현하지_않는다() {
    PushSendResult result =
        sender(FirebaseCallResult.accepted("projects/p/messages/42")).send(message());

    assertThat(result).isEqualTo(new PushSendResult.Accepted("projects/p/messages/42"));
    assertThat(result.getClass().getSimpleName()).doesNotContain("Delivered");
  }

  @Test
  void explicit_429와_5xx는_retry_after를_보존하는_RETRYABLE_FAILURE다() {
    PushSendResult rateLimited =
        sender(
                FirebaseCallResult.failed(
                    FirebaseCallFailure.providerResponse(
                        429, "QUOTA_EXCEEDED", Duration.ofSeconds(17))))
            .send(message());
    PushSendResult serverError =
        sender(
                FirebaseCallResult.failed(
                    FirebaseCallFailure.providerResponse(503, "UNAVAILABLE", null)))
            .send(message());

    assertThat(rateLimited)
        .isEqualTo(
            new PushSendResult.RetryableFailure(
                PushErrorClass.RATE_LIMITED, Duration.ofSeconds(17)));
    assertThat(serverError)
        .isEqualTo(new PushSendResult.RetryableFailure(PushErrorClass.SERVER_ERROR, null));
  }

  @Test
  void request_byte가_전혀_전송되지_않았음이_증명된_pre_connect만_retryable이다() {
    PushSendResult result =
        sender(FirebaseCallResult.failed(FirebaseCallFailure.provenPreConnect())).send(message());

    assertThat(result)
        .isEqualTo(new PushSendResult.RetryableFailure(PushErrorClass.PRE_CONNECT, null));
  }

  @Test
  void post_write_read_timeout_reset_unexpected_EOF는_retry_hint없는_terminal_ACCEPTANCE_UNKNOWN이다() {
    for (String reason : new String[] {"READ_TIMEOUT", "CONNECTION_RESET", "UNEXPECTED_EOF"}) {
      PushSendResult result =
          sender(FirebaseCallResult.failed(FirebaseCallFailure.postWriteAmbiguous(reason)))
              .send(message());

      assertThat(result)
          .as(reason)
          .isEqualTo(new PushSendResult.AcceptanceUnknown(PushErrorClass.POST_WRITE_AMBIGUOUS));
    }
  }

  @Test
  void UNREGISTERED는_영구실패와_token_비활성화_신호로_변환한다() {
    PushSendResult result =
        sender(
                FirebaseCallResult.failed(
                    FirebaseCallFailure.providerResponse(404, "UNREGISTERED", null)))
            .send(message());

    assertThat(result)
        .isEqualTo(new PushSendResult.PermanentFailure(PushErrorClass.TOKEN_UNREGISTERED, true));
  }

  @Test
  void credential은_token을_비활성화하지_않고_validated_payload의_INVALID_ARGUMENT는_token_invalid다() {
    PushSendResult credential =
        sender(
                FirebaseCallResult.failed(
                    FirebaseCallFailure.providerResponse(401, "THIRD_PARTY_AUTH_ERROR", null)))
            .send(message());
    PushSendResult payload =
        sender(
                FirebaseCallResult.failed(
                    FirebaseCallFailure.providerResponse(400, "INVALID_ARGUMENT", null)))
            .send(message());

    assertThat(credential)
        .isEqualTo(new PushSendResult.PermanentFailure(PushErrorClass.CREDENTIAL, false));
    assertThat(payload)
        .isEqualTo(new PushSendResult.PermanentFailure(PushErrorClass.TOKEN_INVALID, true));
  }

  private static FirebasePushMessageSender sender(FirebaseCallResult result) {
    FirebaseMessagingGateway gateway = ignored -> result;
    return new FirebasePushMessageSender(
        gateway,
        new FirebaseMessageMapper(CLOCK),
        new FirebaseErrorClassifier(),
        new FirebasePushTelemetry(new SimpleMeterRegistry(), CLOCK));
  }

  static PushMessage messageForGatewayTest() {
    String tripId = "11111111-1111-4111-8111-111111111111";
    String itemId = "22222222-2222-4222-8222-222222222222";
    return new PushMessage(
        "secret-test-registration-token",
        "출발 알림",
        "앱을 열어 다음 일정을 확인하세요.",
        Map.of(
            "contractVersion",
            "1.0.0",
            "tripId",
            tripId,
            "tripItemId",
            itemId,
            "scheduleVersionId",
            "33333333-3333-4333-8333-333333333333",
            "deepLink",
            "timingjeju://trips/" + tripId + "/live?itemId=" + itemId),
        Duration.ofSeconds(60),
        "trip:" + tripId + ":departure",
        PushPlatformHints.visibleTimeSensitive());
  }

  private static PushMessage message() {
    return messageForGatewayTest();
  }
}
