package com.timingjeju.api.application.datahealth;

public final class ProviderDataHealthException extends RuntimeException {
  public enum Code {
    DATA_HEALTH_UNAVAILABLE
  }

  private final Code code;

  private ProviderDataHealthException(Code code) {
    super(code.name(), null, false, false);
    this.code = code;
  }

  public static ProviderDataHealthException unavailable() {
    return new ProviderDataHealthException(Code.DATA_HEALTH_UNAVAILABLE);
  }

  public Code code() {
    return code;
  }
}
