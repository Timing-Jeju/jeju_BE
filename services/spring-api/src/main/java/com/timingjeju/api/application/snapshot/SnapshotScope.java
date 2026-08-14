package com.timingjeju.api.application.snapshot;

public record SnapshotScope(String provider, String service, String operation, String scopeKey) {
  public SnapshotScope {
    provider = required(provider, "provider", 128);
    service = required(service, "service", 128);
    operation = required(operation, "operation", 128);
    scopeKey = required(scopeKey, "scopeKey", 512);
    if (provider.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + service.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + operation.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + scopeKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > 1024) {
      throw new IllegalArgumentException("snapshot scope가 너무 깁니다.");
    }
  }

  private static String required(String value, String name, int maxBytes) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + "은 필수입니다.");
    }
    String normalized = value.strip();
    if (normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
      throw new IllegalArgumentException(name + "이 너무 깁니다.");
    }
    return normalized;
  }
}
