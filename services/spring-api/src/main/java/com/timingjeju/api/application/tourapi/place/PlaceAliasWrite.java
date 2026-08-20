package com.timingjeju.api.application.tourapi.place;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record PlaceAliasWrite(String alias, String normalizedAlias) {
  public PlaceAliasWrite {
    alias = required(alias, "alias");
    normalizedAlias = required(normalizedAlias, "normalizedAlias");
  }

  private static String required(String value, String field) {
    String result = Objects.requireNonNull(value, field + "는 필수입니다.").strip();
    if (result.isEmpty() || result.getBytes(StandardCharsets.UTF_8).length > 1024) {
      throw new IllegalArgumentException(field + " 형식이 올바르지 않습니다.");
    }
    return result;
  }
}
