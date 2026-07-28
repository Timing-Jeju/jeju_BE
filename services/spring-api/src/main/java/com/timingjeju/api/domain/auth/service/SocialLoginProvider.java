package com.timingjeju.api.domain.auth.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum SocialLoginProvider {
  GOOGLE("google", "Google"),
  KAKAO("kakao", "Kakao"),
  NAVER("custom:naver", "Naver");

  private final String id;
  private final String displayName;

  SocialLoginProvider(String id, String displayName) {
    this.id = id;
    this.displayName = displayName;
  }

  public String id() {
    return id;
  }

  public String displayName() {
    return displayName;
  }

  public static Optional<SocialLoginProvider> fromId(String id) {
    return Arrays.stream(values()).filter(provider -> provider.id.equals(id)).findFirst();
  }

  public static List<SocialLoginProvider> valuesAsList() {
    return List.of(values());
  }
}
