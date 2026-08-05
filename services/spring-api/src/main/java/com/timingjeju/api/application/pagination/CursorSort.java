package com.timingjeju.api.application.pagination;

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
    return key + ":" + direction.name() + ":" + tieBreakerKey;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
