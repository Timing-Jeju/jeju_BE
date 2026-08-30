package com.timingjeju.api.application.trip;

public final class TripException extends RuntimeException {
  private final String code;

  private TripException(String code) {
    super(code, null, false, false);
    this.code = code;
  }

  public static TripException invalidRequest() {
    return new TripException("INVALID_REQUEST");
  }

  public static TripException invalidQuery() {
    return new TripException("INVALID_QUERY_PARAMETER");
  }

  public static TripException invalidCursor() {
    return new TripException("INVALID_CURSOR");
  }

  public static TripException cursorContextMismatch() {
    return new TripException("CURSOR_CONTEXT_MISMATCH");
  }

  public static TripException constraintViolation() {
    return new TripException("TRIP_CONSTRAINT_VIOLATION");
  }

  public static TripException notFound() {
    return new TripException("TRIP_NOT_FOUND");
  }

  public static TripException dataUnavailable() {
    return new TripException("TRIP_DATA_UNAVAILABLE");
  }

  public String code() {
    return code;
  }
}
