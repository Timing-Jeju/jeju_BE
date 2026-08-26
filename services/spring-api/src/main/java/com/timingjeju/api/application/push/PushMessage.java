package com.timingjeju.api.application.push;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record PushMessage(
    String registrationToken,
    String title,
    String body,
    Map<String, String> data,
    Duration ttl,
    String collapseKey,
    PushPlatformHints platformHints) {

  public PushMessage {
    if (registrationToken == null || registrationToken.isBlank()) {
      throw new IllegalArgumentException("registration token은 필수입니다.");
    }
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(body, "body");
    data = Map.copyOf(Objects.requireNonNull(data, "data"));
    Objects.requireNonNull(ttl, "ttl");
    if (collapseKey == null || collapseKey.isBlank()) {
      throw new IllegalArgumentException("collapse key는 필수입니다.");
    }
    Objects.requireNonNull(platformHints, "platformHints");
  }

  @Override
  public String toString() {
    return "PushMessage[registrationToken=[REDACTED], content=[REDACTED], ttl="
        + ttl
        + ", collapseKey=[REDACTED], platformHints="
        + platformHints
        + "]";
  }
}
