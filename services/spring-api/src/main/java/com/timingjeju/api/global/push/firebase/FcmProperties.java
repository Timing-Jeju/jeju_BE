package com.timingjeju.api.global.push.firebase;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.push.fcm")
public record FcmProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("") String projectId,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout,
    @DefaultValue("5s") Duration writeTimeout) {
  private static final Pattern PROJECT_ID = Pattern.compile("^[a-z][a-z0-9-]{4,28}[a-z0-9]$");
  private static final Set<String> PLACEHOLDERS =
      Set.of("changeme", "replace-me", "your-project-id", "firebase-project-id");

  String requiredProjectId() {
    if (projectId == null || projectId.isBlank()) {
      throw new IllegalStateException("FIREBASE_PROJECT_ID는 FCM 활성화 환경에서 필수입니다.");
    }
    String normalized = projectId.strip();
    if (!PROJECT_ID.matcher(normalized).matches() || PLACEHOLDERS.contains(normalized)) {
      throw new IllegalStateException("FIREBASE_PROJECT_ID는 실제 Firebase project ID 형식이어야 합니다.");
    }
    return normalized;
  }

  FcmClientSettings requiredClientSettings() {
    return FcmClientSettings.from(requiredProjectId(), connectTimeout, readTimeout, writeTimeout);
  }

  @Override
  public String toString() {
    return "FcmProperties[enabled="
        + enabled
        + ", projectId="
        + (projectId == null || projectId.isBlank() ? "[EMPTY]" : "[CONFIGURED]")
        + "]";
  }
}
