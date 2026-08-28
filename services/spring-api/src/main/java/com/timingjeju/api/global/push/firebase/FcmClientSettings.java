package com.timingjeju.api.global.push.firebase;

import java.time.Duration;

record FcmClientSettings(
    String projectId, int connectTimeoutMillis, int readTimeoutMillis, int writeTimeoutMillis) {
  private static final Duration MIN_TIMEOUT = Duration.ofMillis(100);
  private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(30);

  static FcmClientSettings from(
      String projectId, Duration connectTimeout, Duration readTimeout, Duration writeTimeout) {
    return new FcmClientSettings(
        projectId,
        checkedMillis("FCM_CONNECT_TIMEOUT", connectTimeout, MAX_CONNECT_TIMEOUT),
        checkedMillis("FCM_READ_TIMEOUT", readTimeout, MAX_READ_TIMEOUT),
        checkedMillis("FCM_WRITE_TIMEOUT", writeTimeout, MAX_READ_TIMEOUT));
  }

  private static int checkedMillis(String name, Duration value, Duration maximum) {
    if (value == null || value.compareTo(MIN_TIMEOUT) < 0 || value.compareTo(maximum) > 0) {
      throw new IllegalStateException(name + "은 100ms 이상 " + maximum.toSeconds() + "초 이하여야 합니다.");
    }
    return Math.toIntExact(value.toMillis());
  }

  @Override
  public String toString() {
    return "FcmClientSettings[projectId=[REDACTED], connectTimeoutMillis="
        + connectTimeoutMillis
        + ", readTimeoutMillis="
        + readTimeoutMillis
        + ", writeTimeoutMillis="
        + writeTimeoutMillis
        + "]";
  }
}
