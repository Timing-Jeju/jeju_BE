package com.timingjeju.api.application.transportevent;

import java.util.Objects;

public final class TransportEventException extends RuntimeException {
  private final String code;

  private TransportEventException(String code) {
    super(code, null, false, false);
    this.code = Objects.requireNonNull(code);
  }

  public static TransportEventException of(String code) {
    return new TransportEventException(code);
  }

  public static TransportEventException invalidRequest() {
    return of("INVALID_REQUEST");
  }

  public String code() {
    return code;
  }
}
