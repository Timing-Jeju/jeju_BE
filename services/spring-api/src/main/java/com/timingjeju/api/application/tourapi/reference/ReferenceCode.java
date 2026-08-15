package com.timingjeju.api.application.tourapi.reference;

import java.util.Map;

public record ReferenceCode(
    String codeType,
    String externalCode,
    String parentExternalCode,
    String name,
    String path,
    Map<String, Object> attributes) {

  public ReferenceCode {
    codeType = required(codeType, "codeType");
    externalCode = required(externalCode, "externalCode");
    parentExternalCode = optional(parentExternalCode, "parentExternalCode");
    name = required(name, "name");
    path = optional(path, "path");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  private static String required(String value, String field) {
    String normalized = optional(value, field);
    if (normalized == null) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
    return normalized;
  }

  private static String optional(String value, String field) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    if (normalized.isEmpty()
        || normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
    return normalized;
  }
}
