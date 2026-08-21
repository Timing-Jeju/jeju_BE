package com.timingjeju.api.domain.places.model;

import java.util.Set;

public final class NearbyStopProjectionContract {

  public static final int PROVIDER_MAX_CODE_POINTS = 128;
  private static final Set<String> LINK_METHODS =
      Set.of("spatial_radius", "fixture", "manual", "api_nearby");

  private NearbyStopProjectionContract() {}

  public static String requireProvider(String value) {
    String provider = requireText(value, "provider");
    if (provider.codePointCount(0, provider.length()) > PROVIDER_MAX_CODE_POINTS) {
      throw new IllegalArgumentException("provider는 Unicode code point 128개 이하여야 합니다.");
    }
    return provider;
  }

  public static String requireLinkMethod(String value) {
    String linkMethod = requireText(value, "linkMethod");
    if (!LINK_METHODS.contains(linkMethod)) {
      throw new IllegalArgumentException("linkMethod가 공개 계약 enum에 속하지 않습니다.");
    }
    return linkMethod;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + "은 필수입니다.");
    }
    return value;
  }
}
