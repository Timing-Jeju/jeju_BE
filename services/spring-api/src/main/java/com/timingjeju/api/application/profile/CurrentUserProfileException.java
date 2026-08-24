package com.timingjeju.api.application.profile;

public final class CurrentUserProfileException extends RuntimeException {

  private final String code;

  private CurrentUserProfileException(String code) {
    super(null, null, false, false);
    this.code = code;
  }

  public static CurrentUserProfileException invalidRequest() {
    return new CurrentUserProfileException("INVALID_PROFILE_LEGAL_REQUEST");
  }

  public static CurrentUserProfileException dataUnavailable() {
    return new CurrentUserProfileException("PROFILE_DATA_UNAVAILABLE");
  }

  public String code() {
    return code;
  }
}
