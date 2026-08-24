package com.timingjeju.api.application.profile;

import java.util.Objects;

public record ProvisioningSocialAccount(
    String provider, String providerUserId, String email, String nickname, String profileImageUrl) {

  public ProvisioningSocialAccount {
    Objects.requireNonNull(provider, "provider must not be null");
    Objects.requireNonNull(providerUserId, "providerUserId must not be null");
  }

  public String publicProvider() {
    return "naver".equals(provider) ? "custom:naver" : provider;
  }
}
