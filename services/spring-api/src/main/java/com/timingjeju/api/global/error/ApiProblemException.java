package com.timingjeju.api.global.error;

public final class ApiProblemException extends RuntimeException {

  private final String code;

  public ApiProblemException(String code) {
    super(null, null, false, false);
    this.code = requireCode(code);
  }

  public String code() {
    return code;
  }

  private static String requireCode(String code) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    return code;
  }
}
