package com.timingjeju.api.domain.places.exception;

public final class PlaceDetailException extends RuntimeException {

  private final String code;

  public PlaceDetailException(String code) {
    super(null, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
