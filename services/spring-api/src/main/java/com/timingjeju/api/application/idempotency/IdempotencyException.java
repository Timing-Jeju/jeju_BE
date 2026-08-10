package com.timingjeju.api.application.idempotency;

import java.util.OptionalInt;

public final class IdempotencyException extends RuntimeException {

  private final String code;
  private final int status;
  private final OptionalInt retryAfterSeconds;

  private IdempotencyException(String code, int status, OptionalInt retryAfterSeconds) {
    super(null, null, false, false);
    this.code = code;
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public static IdempotencyException required() {
    return new IdempotencyException("IDEMPOTENCY_KEY_REQUIRED", 400, OptionalInt.empty());
  }

  public static IdempotencyException invalid() {
    return new IdempotencyException("IDEMPOTENCY_KEY_INVALID", 400, OptionalInt.empty());
  }

  public static IdempotencyException reused() {
    return new IdempotencyException("IDEMPOTENCY_KEY_REUSED", 409, OptionalInt.empty());
  }

  public static IdempotencyException processing() {
    return new IdempotencyException("IDEMPOTENCY_KEY_REUSED", 409, OptionalInt.of(1));
  }

  public String code() {
    return code;
  }

  public int status() {
    return status;
  }

  public OptionalInt retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
