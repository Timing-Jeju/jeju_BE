package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class JdkSupabaseAdminHttpTransport implements SupabaseAdminHttpTransport {
  private final HttpClient client;

  public JdkSupabaseAdminHttpTransport(SupabaseAdminSettings settings) {
    this(
        HttpClient.newBuilder()
            .connectTimeout(settings.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  JdkSupabaseAdminHttpTransport(HttpClient client) {
    this.client = java.util.Objects.requireNonNull(client);
  }

  @Override
  public SupabaseAdminHttpResponse exchange(
      HttpRequest request, byte[] requestBody, int maximumBodyBytes) {
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        byte[] bounded = body.readNBytes(maximumBodyBytes + 1);
        if (bounded.length > maximumBodyBytes) {
          throw DeletionOperationException.retryable("SUPABASE_RESPONSE_TOO_LARGE");
        }
        return new SupabaseAdminHttpResponse(response.statusCode(), bounded);
      }
    } catch (DeletionOperationException failure) {
      throw failure;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw DeletionOperationException.retryable("SUPABASE_NETWORK_FAILURE");
    } catch (Exception failure) {
      throw DeletionOperationException.retryable("SUPABASE_NETWORK_FAILURE");
    }
  }
}
