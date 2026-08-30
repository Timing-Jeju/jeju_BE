package com.timingjeju.api.global.push.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.timingjeju.api.application.push.PushMessage;
import com.timingjeju.api.application.push.PushPlatformHints;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("unit")
class FirebasePushTelemetryTest {

  @Test
  void 로그와_metric에는_token_credential_notification_data_provider_body를_기록하지_않는다() {
    String token = "sensitive-registration-token-for-test";
    String title = "sensitive-notification-title";
    String body = "sensitive-notification-body";
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Clock clock = Clock.fixed(Instant.parse("2026-08-26T04:00:00Z"), ZoneOffset.UTC);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger logger = (Logger) LoggerFactory.getLogger(FirebasePushTelemetry.class);
    logger.addAppender(appender);
    try {
      FirebasePushMessageSender sender =
          new FirebasePushMessageSender(
              ignored ->
                  FirebaseCallResult.failed(
                      FirebaseCallFailure.providerResponse(503, "UNAVAILABLE", null)),
              new FirebaseMessageMapper(clock),
              new FirebaseErrorClassifier(),
              new FirebasePushTelemetry(registry, clock));

      sender.send(message(token, title, body));

      String logs = appender.list.toString();
      String meters =
          registry.getMeters().stream()
              .map(meter -> meter.getId().getName() + meter.getId().getTags())
              .toList()
              .toString();
      assertThat(logs)
          .contains("outcome=retryable_failure", "errorClass=SERVER_ERROR")
          .doesNotContain(token, title, body, "projects/", "UNAVAILABLE");
      assertThat(meters)
          .contains("provider=firebase", "outcome=retryable_failure", "errorClass=server_error")
          .doesNotContain(token, title, body);
    } finally {
      logger.detachAppender(appender);
    }
  }

  private static PushMessage message(String token, String title, String body) {
    String tripId = "aaaaaaaa-1111-4111-8111-111111111111";
    String itemId = "22222222-2222-4222-8222-222222222222";
    return new PushMessage(
        token,
        title,
        body,
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
}
