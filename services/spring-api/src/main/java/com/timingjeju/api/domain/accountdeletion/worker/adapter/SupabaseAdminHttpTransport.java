package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import java.net.http.HttpRequest;

@FunctionalInterface
public interface SupabaseAdminHttpTransport {
  SupabaseAdminHttpResponse exchange(HttpRequest request, byte[] requestBody, int maximumBodyBytes);
}
