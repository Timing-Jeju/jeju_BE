package com.timingjeju.api.application.idempotency;

import java.util.Objects;
import java.util.UUID;

public record IdempotencyScope(
    UUID ownerSub, String method, String normalizedPath, UUID idempotencyKey) {

  public IdempotencyScope {
    Objects.requireNonNull(ownerSub, "ownerSub must not be null");
    Objects.requireNonNull(method, "method must not be null");
    Objects.requireNonNull(normalizedPath, "normalizedPath must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
  }
}
