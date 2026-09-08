package com.timingjeju.api.application.accommodation;

public final class AccommodationException extends RuntimeException {
  private final String code;

  private AccommodationException(String code) {
    super(code, null, false, false);
    this.code = code;
  }

  public static AccommodationException of(String code) {
    return new AccommodationException(code);
  }

  public static AccommodationException invalidRequest() {
    return of("INVALID_REQUEST");
  }

  public String code() {
    return code;
  }
}
