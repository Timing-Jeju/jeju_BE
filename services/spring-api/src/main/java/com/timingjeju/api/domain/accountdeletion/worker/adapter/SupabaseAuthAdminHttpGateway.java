package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import java.net.http.HttpRequest;
import java.util.UUID;

public final class SupabaseAuthAdminHttpGateway implements SupabaseAuthAdminGateway {
  private static final int MAXIMUM_BODY_BYTES = 64 * 1024;
  private final SupabaseAdminSettings settings;
  private final SupabaseAdminHttpTransport transport;

  public SupabaseAuthAdminHttpGateway(
      SupabaseAdminSettings settings, SupabaseAdminHttpTransport transport) {
    this.settings = java.util.Objects.requireNonNull(settings);
    this.transport = java.util.Objects.requireNonNull(transport);
  }

  @Override
  public ExternalDeletionResult deleteUser(AuthSubject subject) {
    String userId = canonicalSubject(subject);
    HttpRequest request =
        authorized("/auth/v1/admin/users/" + userId)
            .DELETE()
            .timeout(settings.readTimeout())
            .build();
    SupabaseAdminHttpResponse response =
        transport.exchange(request, new byte[0], MAXIMUM_BODY_BYTES);
    if (response.status() >= 200 && response.status() < 300) {
      return ExternalDeletionResult.DELETED;
    }
    if (response.status() == 404) {
      return ExternalDeletionResult.ALREADY_ABSENT;
    }
    if (response.status() == 429 || response.status() >= 500) {
      throw DeletionOperationException.retryable("SUPABASE_AUTH_DELETE_UNAVAILABLE");
    }
    throw DeletionOperationException.terminal("SUPABASE_AUTH_DELETE_REJECTED");
  }

  HttpRequest.Builder authorized(String path) {
    return HttpRequest.newBuilder(settings.baseUrl().resolve(path))
        .header("Authorization", "Bearer " + settings.serviceRoleKey())
        .header("apikey", settings.serviceRoleKey())
        .header("Accept", "application/json");
  }

  static String canonicalSubject(AuthSubject subject) {
    try {
      String value = java.util.Objects.requireNonNull(subject).value();
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException();
      }
      return value;
    } catch (RuntimeException failure) {
      throw DeletionOperationException.terminal("INVALID_AUTH_SUBJECT");
    }
  }
}
