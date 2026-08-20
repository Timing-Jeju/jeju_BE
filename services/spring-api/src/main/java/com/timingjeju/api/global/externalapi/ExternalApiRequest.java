package com.timingjeju.api.global.externalapi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ExternalApiRequest {

  private static final Pattern SAFE_RELATIVE_PATH =
      Pattern.compile("[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*");
  private static final Pattern SAFE_QUERY_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9._~-]{0,63}");

  private final ExternalApiHttpMethod method;
  private final ExternalApiOperation operation;
  private final String relativePath;
  private final Map<String, String> queryParameters;
  private final ExternalApiResponseFormat responseFormat;

  private ExternalApiRequest(
      ExternalApiHttpMethod method,
      ExternalApiOperation operation,
      String relativePath,
      Map<String, String> queryParameters,
      ExternalApiResponseFormat responseFormat) {
    this.method = Objects.requireNonNull(method, "method는 필수입니다.");
    this.operation = Objects.requireNonNull(operation, "operation은 필수입니다.");
    this.relativePath = validatePath(relativePath);
    this.queryParameters = validateQuery(queryParameters);
    this.responseFormat = Objects.requireNonNull(responseFormat, "responseFormat은 필수입니다.");
  }

  public static ExternalApiRequest get(
      ExternalApiOperation operation,
      String relativePath,
      Map<String, String> queryParameters,
      ExternalApiResponseFormat responseFormat) {
    return of(ExternalApiHttpMethod.GET, operation, relativePath, queryParameters, responseFormat);
  }

  public static ExternalApiRequest of(
      ExternalApiHttpMethod method,
      ExternalApiOperation operation,
      String relativePath,
      Map<String, String> queryParameters,
      ExternalApiResponseFormat responseFormat) {
    return new ExternalApiRequest(method, operation, relativePath, queryParameters, responseFormat);
  }

  ExternalApiHttpMethod method() {
    return method;
  }

  ExternalApiOperation operation() {
    return operation;
  }

  String relativePath() {
    return relativePath;
  }

  Map<String, String> queryParameters() {
    return queryParameters;
  }

  ExternalApiResponseFormat responseFormat() {
    return responseFormat;
  }

  private static String validatePath(String value) {
    if (value == null) {
      throw new IllegalArgumentException("외부 API path는 안전한 상대 경로여야 합니다.");
    }
    if (value.isBlank() || value.startsWith("/") || !SAFE_RELATIVE_PATH.matcher(value).matches()) {
      throw new IllegalArgumentException("외부 API path는 안전한 상대 경로여야 합니다.");
    }
    for (String segment : value.split("/")) {
      if (".".equals(segment) || "..".equals(segment)) {
        throw new IllegalArgumentException("외부 API path에는 상대 경로 이동을 사용할 수 없습니다.");
      }
    }
    return value;
  }

  private static Map<String, String> validateQuery(Map<String, String> input) {
    if (input == null) {
      throw new IllegalArgumentException("queryParameters는 null일 수 없습니다.");
    }
    Map<String, String> result = new LinkedHashMap<>();
    input.forEach(
        (name, value) -> {
          if (name == null
              || name.isBlank()
              || !("_type".equals(name) || SAFE_QUERY_NAME.matcher(name).matches())
              || "serviceKey".equalsIgnoreCase(name)
              || "appKey".equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("외부 API query 이름이 허용되지 않습니다.");
          }
          if (value == null || containsControl(value)) {
            throw new IllegalArgumentException("외부 API query는 허용되지 않습니다.");
          }
          if ("_type".equals(name) && !"json".equals(value)) {
            throw new IllegalArgumentException("외부 API query는 허용되지 않습니다.");
          }
          if (result.putIfAbsent(name, value) != null) {
            throw new IllegalArgumentException("외부 API query 이름은 중복될 수 없습니다.");
          }
        });
    return Map.copyOf(result);
  }

  private static boolean containsControl(String value) {
    return value.chars().anyMatch(character -> Character.isISOControl(character));
  }

  @Override
  public String toString() {
    return "ExternalApiRequest[method="
        + method
        + ", operation="
        + operation
        + ", relativePath="
        + relativePath
        + ", queryParameters=[REDACTED], responseFormat="
        + responseFormat
        + "]";
  }
}
