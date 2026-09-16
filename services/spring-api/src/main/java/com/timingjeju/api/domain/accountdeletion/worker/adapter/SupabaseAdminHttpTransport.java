package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import java.net.http.HttpRequest;

@FunctionalInterface
public interface SupabaseAdminHttpTransport {
  SupabaseAdminHttpResponse exchange(HttpRequest request, byte[] requestBody, int maximumBodyBytes);

  default SupabaseAdminHttpResponse exchange(
      HttpRequest request, byte[] requestBody, int maximumBodyBytes, Runnable inFlightCheckpoint) {
    java.util.Objects.requireNonNull(inFlightCheckpoint);
    return exchange(request, requestBody, maximumBodyBytes);
  }
}
