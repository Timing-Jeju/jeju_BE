package com.timingjeju.api.application.idempotency;

import java.util.List;

public final class IdempotencyResponse {

  public static final int MAX_BODY_BYTES = 1_048_576;

  private final int status;
  private final List<IdempotencyHeader> headers;
  private final byte[] body;

  public IdempotencyResponse(int status, List<IdempotencyHeader> headers, byte[] body) {
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("HTTP status가 올바르지 않습니다.");
    }
    this.status = status;
    this.headers = List.copyOf(headers);
    this.body = body.clone();
    if (this.body.length > MAX_BODY_BYTES) {
      throw new IllegalArgumentException("response body는 1 MiB 이하여야 합니다.");
    }
  }

  public int status() {
    return status;
  }

  public List<IdempotencyHeader> headers() {
    return headers;
  }

  public byte[] body() {
    return body.clone();
  }

  public boolean isUnexpectedServerError() {
    return status >= 500;
  }
}
