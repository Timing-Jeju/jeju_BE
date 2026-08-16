package com.timingjeju.api.application.tago.stop;

public final class TagoStopImportException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    INVALID_PROVIDER_RESPONSE,
    JEJU_CITY_NOT_FOUND
  }

  private final Code code;

  private TagoStopImportException(Code code) {
    super(code.name());
    this.code = code;
  }

  private TagoStopImportException(Code code, Throwable cause) {
    super(code.name(), cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public static TagoStopImportException invalidRequest() {
    return new TagoStopImportException(Code.INVALID_REQUEST);
  }

  public static TagoStopImportException invalidResponse() {
    return new TagoStopImportException(Code.INVALID_PROVIDER_RESPONSE);
  }

  public static TagoStopImportException invalidResponse(Throwable cause) {
    return new TagoStopImportException(Code.INVALID_PROVIDER_RESPONSE, cause);
  }

  public static TagoStopImportException jejuNotFound() {
    return new TagoStopImportException(Code.JEJU_CITY_NOT_FOUND);
  }
}
