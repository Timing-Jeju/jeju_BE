package com.timingjeju.api.application.importing;

public record ImportRunScope(String provider, String service, String operation, String scopeKey) {

  public ImportRunScope {
    provider = requireText(provider, "provider");
    service = requireText(service, "service");
    operation = requireText(operation, "operation");
    scopeKey = requireText(scopeKey, "scopeKey");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + "는 필수입니다.");
    }
    return value.trim();
  }
}
