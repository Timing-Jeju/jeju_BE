package com.timingjeju.api.global.push.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.push.PushMessage;
import com.timingjeju.api.application.push.PushPlatformHints;
import com.timingjeju.api.application.push.PushSendResult;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "FCM_LIVE_TEST_ENABLED", matches = "true")
class FirebaseLiveEnvironmentIntegrationTest {

  @Test
  void 명시적_격리환경에서만_ADC로_실제_Firebase_endpoint를_검증한다() {
    String projectId = requiredEnvironment("FIREBASE_PROJECT_ID");
    String registrationToken = requiredEnvironment("FCM_LIVE_TEST_REGISTRATION_TOKEN");
    FirebaseMessagingGateway gateway =
        new DefaultFirebaseAdminClientFactory()
            .create(
                FcmClientSettings.from(
                    projectId, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5)),
                java.time.Clock.systemUTC());
    FirebasePushMessageSender sender =
        new FirebasePushMessageSender(
            gateway,
            new FirebaseMessageMapper(java.time.Clock.systemUTC()),
            new FirebaseErrorClassifier(),
            new FirebasePushTelemetry(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                java.time.Clock.systemUTC()));

    PushSendResult result = sender.send(liveMessage(registrationToken));

    assertThat(result).isNotNull();
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + "가 명시적 live 검증에 필요합니다.");
    }
    return value;
  }

  private static PushMessage liveMessage(String token) {
    String tripId = "aaaaaaaa-1111-4111-8111-111111111111";
    String itemId = "22222222-2222-4222-8222-222222222222";
    return new PushMessage(
        token,
        "Timing Jeju FCM 검증",
        "명시적으로 실행된 Firebase endpoint 검증 메시지입니다.",
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
