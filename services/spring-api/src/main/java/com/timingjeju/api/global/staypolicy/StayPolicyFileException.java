package com.timingjeju.api.global.staypolicy;

public final class StayPolicyFileException extends RuntimeException {
  public StayPolicyFileException(String message) {
    super(message);
  }

  public StayPolicyFileException(String message, Throwable cause) {
    super(message, cause);
  }
}
