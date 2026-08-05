package com.timingjeju.api.global.error;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public record ProblemDefinition(URI type, String title, int status, String code, String detail) {

  private static final String TYPE_BASE = "https://api.timing-jeju.example/problems/";

  public static ProblemDefinition forCode(String code, String title, int status, String detail) {
    requireText(code, "code");
    URI type = URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    return new ProblemDefinition(type, title, status, code, detail);
  }

  public ProblemDefinition {
    Objects.requireNonNull(type, "type must not be null");
    requireText(title, "title");
    if (status < 400 || status > 599) {
      throw new IllegalArgumentException("status must be a 4xx or 5xx value");
    }
    requireText(code, "code");
    requireText(detail, "detail");
  }

  static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
