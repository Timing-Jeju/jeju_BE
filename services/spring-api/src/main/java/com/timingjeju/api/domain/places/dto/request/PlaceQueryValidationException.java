package com.timingjeju.api.domain.places.dto.request;

public final class PlaceQueryValidationException extends RuntimeException {

  private final String code;

  PlaceQueryValidationException(String code) {
    super(null, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
