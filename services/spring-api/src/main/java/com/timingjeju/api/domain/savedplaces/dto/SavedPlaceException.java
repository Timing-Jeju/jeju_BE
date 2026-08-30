package com.timingjeju.api.domain.savedplaces.dto;

public final class SavedPlaceException extends RuntimeException {
  private final String code;

  private SavedPlaceException(String code) {
    super(null, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static SavedPlaceException invalidRequest() {
    return new SavedPlaceException("INVALID_REQUEST");
  }

  public static SavedPlaceException of(String code) {
    return new SavedPlaceException(code);
  }
}
