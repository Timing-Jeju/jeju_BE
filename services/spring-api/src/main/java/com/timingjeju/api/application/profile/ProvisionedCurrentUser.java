package com.timingjeju.api.application.profile;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProvisionedCurrentUser(UUID userId, List<String> providers) {

  public ProvisionedCurrentUser {
    Objects.requireNonNull(userId, "userId must not be null");
    providers = List.copyOf(providers);
  }
}
