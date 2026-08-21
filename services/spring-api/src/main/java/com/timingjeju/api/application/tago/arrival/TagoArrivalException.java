package com.timingjeju.api.application.tago.arrival;

public final class TagoArrivalException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    INVALID_PROVIDER_RESPONSE,
    EMPTY_RESULT,
    RATE_LIMITED,
    TIMEOUT,
    PROVIDER_UNAVAILABLE,
    DATA_UNAVAILABLE
  }

  private final Code code;

  private TagoArrivalException(Code code) {
    super(code.name(), null, false, false);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public static TagoArrivalException invalidRequest() {
    return new TagoArrivalException(Code.INVALID_REQUEST);
  }

  public static TagoArrivalException invalidResponse() {
    return new TagoArrivalException(Code.INVALID_PROVIDER_RESPONSE);
  }

  public static TagoArrivalException emptyResult() {
    return new TagoArrivalException(Code.EMPTY_RESULT);
  }

  public static TagoArrivalException rateLimited() {
    return new TagoArrivalException(Code.RATE_LIMITED);
  }

  public static TagoArrivalException timeout() {
    return new TagoArrivalException(Code.TIMEOUT);
  }

  public static TagoArrivalException providerUnavailable() {
    return new TagoArrivalException(Code.PROVIDER_UNAVAILABLE);
  }

  public static TagoArrivalException dataUnavailable() {
    return new TagoArrivalException(Code.DATA_UNAVAILABLE);
  }
}
