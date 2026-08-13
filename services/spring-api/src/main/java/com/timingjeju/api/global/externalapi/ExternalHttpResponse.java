package com.timingjeju.api.global.externalapi;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ExternalHttpResponse implements AutoCloseable {

  private final int status;
  private final Map<String, List<String>> headers;
  private final InputStream body;

  ExternalHttpResponse(int status, Map<String, List<String>> headers, InputStream body) {
    this.status = status;
    this.headers = immutableHeaders(headers);
    this.body = Objects.requireNonNull(body, "body는 필수입니다.");
  }

  int status() {
    return status;
  }

  Map<String, List<String>> headers() {
    return headers;
  }

  Optional<String> firstHeader(String name) {
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .flatMap(entry -> entry.getValue().stream())
        .findFirst();
  }

  InputStream body() {
    return body;
  }

  @Override
  public void close() throws IOException {
    body.close();
  }

  private static Map<String, List<String>> immutableHeaders(Map<String, List<String>> input) {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    input.forEach((name, values) -> copy.put(name, List.copyOf(new ArrayList<>(values))));
    return Map.copyOf(copy);
  }
}
