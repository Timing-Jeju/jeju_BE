package com.timingjeju.api.application.pagination;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public record CursorSort(String key, CursorDirection direction, String tieBreakerKey) {

  public CursorSort {
    key = requireText(key, "key");
    Objects.requireNonNull(direction, "direction must not be null");
    tieBreakerKey = requireText(tieBreakerKey, "tieBreakerKey");
  }

  public static CursorSort asc(String key, String tieBreakerKey) {
    return new CursorSort(key, CursorDirection.ASC, tieBreakerKey);
  }

  public static CursorSort desc(String key, String tieBreakerKey) {
    return new CursorSort(key, CursorDirection.DESC, tieBreakerKey);
  }

  String token() {
    return "v1."
        + encodeComponent(key)
        + "."
        + direction.name()
        + "."
        + encodeComponent(tieBreakerKey);
  }

  private static String encodeComponent(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
