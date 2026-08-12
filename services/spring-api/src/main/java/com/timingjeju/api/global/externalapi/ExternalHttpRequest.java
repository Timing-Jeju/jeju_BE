package com.timingjeju.api.global.externalapi;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

record ExternalHttpRequest(
    ExternalApiProvider provider,
    ExternalApiHttpMethod method,
    URI uri,
    Map<String, String> headers) {

  ExternalHttpRequest {
    Objects.requireNonNull(provider, "provider는 필수입니다.");
    Objects.requireNonNull(method, "method는 필수입니다.");
    Objects.requireNonNull(uri, "uri는 필수입니다.");
    headers = Map.copyOf(new LinkedHashMap<>(headers));
  }

  @Override
  public String toString() {
    return "ExternalHttpRequest[provider="
        + provider
        + ", method="
        + method
        + ", target=[REDACTED], headers=[REDACTED]]";
  }
}
