package com.timingjeju.api.domain.auth.exception;

public final class NaverUserInfoException extends RuntimeException {

  private final NaverUserInfoFailureCode code;

  public NaverUserInfoException(NaverUserInfoFailureCode code) {
    super(code.name(), null, false, false);
    this.code = code;
  }

  public NaverUserInfoFailureCode code() {
    return code;
  }
}
