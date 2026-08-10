package com.timingjeju.api.application.pagination;

public record CursorContext(String endpoint, CursorSort sort, String normalizedFilterFingerprint) {

  public CursorContext {
    endpoint = requireText(endpoint, "endpoint");
    if (sort == null) {
      throw new IllegalArgumentException("sort must not be null");
    }
    normalizedFilterFingerprint = requireFingerprint(normalizedFilterFingerprint);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String requireFingerprint(String value) {
    requireText(value, "normalizedFilterFingerprint");
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "normalizedFilterFingerprint must be SHA-256 lowercase hex");
    }
    return value;
  }
}
