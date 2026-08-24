package com.timingjeju.api.application.profile;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProfileProvisioningRequest(
    UUID userId,
    String email,
    String nickname,
    String profileImageUrl,
    List<ProvisioningSocialAccount> socialAccounts,
    Instant requestedAt) {

  public ProfileProvisioningRequest {
    Objects.requireNonNull(userId, "userId must not be null");
    socialAccounts = List.copyOf(socialAccounts);
    Objects.requireNonNull(requestedAt, "requestedAt must not be null");
  }
}
