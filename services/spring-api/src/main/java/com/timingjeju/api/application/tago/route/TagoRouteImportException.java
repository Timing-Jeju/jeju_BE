package com.timingjeju.api.application.tago.route;

public final class TagoRouteImportException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    INVALID_PROVIDER_RESPONSE,
    STOP_SCOPE_MISMATCH
  }

  private final Code code;

  private TagoRouteImportException(Code code) {
    super(code.name());
    this.code = code;
  }

  private TagoRouteImportException(Code code, Throwable cause) {
    super(code.name(), cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public static TagoRouteImportException invalidRequest() {
    return new TagoRouteImportException(Code.INVALID_REQUEST);
  }

  public static TagoRouteImportException invalidResponse() {
    return new TagoRouteImportException(Code.INVALID_PROVIDER_RESPONSE);
  }

  public static TagoRouteImportException invalidResponse(Throwable cause) {
    return new TagoRouteImportException(Code.INVALID_PROVIDER_RESPONSE, cause);
  }

  public static TagoRouteImportException stopScopeMismatch() {
    return new TagoRouteImportException(Code.STOP_SCOPE_MISMATCH);
  }
}
