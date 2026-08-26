package com.timingjeju.api.global.push.firebase;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidConfig.Priority;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.timingjeju.api.application.push.PushMessage;
import com.timingjeju.api.application.push.PushPlatformHints;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class FirebaseMessageMapper {
  private static final Set<String> DATA_KEYS =
      Set.of("contractVersion", "tripId", "tripItemId", "scheduleVersionId", "deepLink");
  private static final String CONTRACT_VERSION = "1.0.0";
  private static final String FALLBACK_TITLE = "출발 알림";
  private static final String FALLBACK_BODY = "앱을 열어 다음 일정을 확인하세요.";
  private static final long MAX_TTL_SECONDS = 900;

  private final Clock clock;

  FirebaseMessageMapper(Clock clock) {
    this.clock = clock;
  }

  Message map(PushMessage source) {
    validateData(source.data());
    long ttlSeconds = validateTtl(source.ttl());
    String tripId = source.data().get("tripId");
    String expectedCollapseKey = "trip:" + tripId + ":departure";
    if (!expectedCollapseKey.equals(source.collapseKey())) {
      throw new IllegalArgumentException("collapse key가 canonical trip departure 형식이 아닙니다.");
    }

    DisplayText display = displayText(source.title(), source.body());
    Instant expiresAt = clock.instant().plusSeconds(ttlSeconds);
    AndroidConfig android =
        AndroidConfig.builder()
            .setPriority(androidPriority(source.platformHints()))
            .setTtl(Duration.ofSeconds(ttlSeconds).toMillis())
            .setCollapseKey(source.collapseKey())
            .build();
    ApnsConfig apns =
        ApnsConfig.builder()
            .putHeader("apns-expiration", Long.toString(expiresAt.getEpochSecond()))
            .putHeader("apns-collapse-id", source.collapseKey())
            .setAps(apnsAps(source.platformHints()))
            .build();

    return Message.builder()
        .setToken(source.registrationToken())
        .setNotification(
            Notification.builder().setTitle(display.title()).setBody(display.body()).build())
        .putAllData(source.data())
        .setAndroidConfig(android)
        .setApnsConfig(apns)
        .build();
  }

  private static Priority androidPriority(PushPlatformHints hints) {
    return hints.androidPriority() == PushPlatformHints.AndroidPriority.HIGH
        ? Priority.HIGH
        : Priority.NORMAL;
  }

  private static Aps apnsAps(PushPlatformHints hints) {
    Aps.Builder builder = Aps.builder();
    if (hints.apnsPresentation() == PushPlatformHints.ApnsPresentation.ALERT_WITH_SOUND) {
      builder.setSound("default");
    }
    return builder.build();
  }

  private static void validateData(Map<String, String> data) {
    if (!data.keySet().equals(DATA_KEYS)) {
      throw new IllegalArgumentException("FCM data schema는 정확한 다섯 key만 허용합니다.");
    }
    int totalBytes = 0;
    for (Map.Entry<String, String> entry : data.entrySet()) {
      String value = entry.getValue();
      if (value == null || containsControl(value)) {
        throw new IllegalArgumentException("FCM data value는 control 문자가 없는 string이어야 합니다.");
      }
      int keyBytes = utf8Bytes(entry.getKey());
      int valueBytes = utf8Bytes(value);
      if (keyBytes > 64 || valueBytes > 512) {
        throw new IllegalArgumentException("FCM data key/value UTF-8 budget을 초과했습니다.");
      }
      totalBytes = Math.addExact(totalBytes, Math.addExact(keyBytes, valueBytes));
    }
    if (totalBytes > 2048) {
      throw new IllegalArgumentException("FCM data 전체 UTF-8 budget을 초과했습니다.");
    }
    if (!CONTRACT_VERSION.equals(data.get("contractVersion"))) {
      throw new IllegalArgumentException("지원하지 않는 FCM contractVersion입니다.");
    }
    requireCanonicalUuid(data.get("tripId"));
    requireCanonicalUuid(data.get("tripItemId"));
    requireCanonicalUuid(data.get("scheduleVersionId"));
    String expectedDeepLink =
        "timingjeju://trips/" + data.get("tripId") + "/live?itemId=" + data.get("tripItemId");
    if (!expectedDeepLink.equals(data.get("deepLink"))) {
      throw new IllegalArgumentException("FCM deepLink가 canonical 형식이 아닙니다.");
    }
  }

  private static void requireCanonicalUuid(String value) {
    try {
      if (value == null || !UUID.fromString(value).toString().equals(value)) {
        throw new IllegalArgumentException("FCM 식별자는 canonical UUID여야 합니다.");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("FCM 식별자는 canonical UUID여야 합니다.");
    }
  }

  private static long validateTtl(Duration ttl) {
    long seconds;
    try {
      seconds = ttl.toSeconds();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("FCM TTL 범위가 유효하지 않습니다.");
    }
    if (ttl.isZero()
        || ttl.isNegative()
        || !ttl.equals(Duration.ofSeconds(seconds))
        || seconds > MAX_TTL_SECONDS) {
      throw new IllegalArgumentException("FCM TTL은 1초 이상 900초 이하여야 합니다.");
    }
    return seconds;
  }

  private static DisplayText displayText(String title, String body) {
    if (containsControl(title)
        || containsControl(body)
        || utf8Bytes(title) > 80
        || utf8Bytes(body) > 256) {
      return new DisplayText(FALLBACK_TITLE, FALLBACK_BODY);
    }
    return new DisplayText(title, body);
  }

  private static boolean containsControl(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private static int utf8Bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private record DisplayText(String title, String body) {}
}
