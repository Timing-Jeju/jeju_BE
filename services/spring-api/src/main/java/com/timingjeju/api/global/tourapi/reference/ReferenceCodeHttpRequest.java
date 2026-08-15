package com.timingjeju.api.global.tourapi.reference;

import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.util.Map;
import java.util.Objects;

record ReferenceCodeHttpRequest(
    ExternalApiOperation operation,
    String relativePath,
    Map<String, String> queryParameters,
    ExternalApiResponseFormat format) {
  ReferenceCodeHttpRequest {
    operation = Objects.requireNonNull(operation, "operation은 필수입니다.");
    if (relativePath == null || relativePath.isBlank()) {
      throw new IllegalArgumentException("relativePath는 필수입니다.");
    }
    queryParameters = Map.copyOf(Objects.requireNonNull(queryParameters, "query는 필수입니다."));
    if (queryParameters.keySet().stream()
        .anyMatch(
            key ->
                key.equalsIgnoreCase("serviceKey")
                    || key.equalsIgnoreCase("apiKey")
                    || key.equalsIgnoreCase("Authorization"))) {
      throw new IllegalArgumentException("credential query는 허용되지 않습니다.");
    }
    format = Objects.requireNonNull(format, "format은 필수입니다.");
  }
}
