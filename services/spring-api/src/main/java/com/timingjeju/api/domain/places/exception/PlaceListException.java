package com.timingjeju.api.domain.places.exception;

public final class PlaceListException extends RuntimeException {

  private final String code;

  public PlaceListException(String code) {
    super(null, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
