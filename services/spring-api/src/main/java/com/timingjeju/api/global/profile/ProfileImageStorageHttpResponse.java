package com.timingjeju.api.global.profile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

record ProfileImageStorageHttpResponse(int status, Map<String, List<String>> headers, byte[] body) {

  ProfileImageStorageHttpResponse {
    headers = Map.copyOf(headers);
    body = body.clone();
  }

  Optional<String> singleHeader(String name) {
    List<String> values =
        headers.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(List.of());
    return values.size() == 1 ? Optional.of(values.getFirst()) : Optional.empty();
  }

  @Override
  public byte[] body() {
    return body.clone();
  }
}
