package com.timingjeju.api.application.profile;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CurrentUserProfile(
    UUID userId,
    String email,
    String nickname,
    String profileImageUrl,
    String locale,
    List<String> providers,
    boolean onboardingCompleted,
    Instant updatedAt) {

  public CurrentUserProfile {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(locale, "locale must not be null");
    providers = List.copyOf(providers);
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }
}
