package com.timingjeju.api.application.schedule;

public final class ScheduleException extends RuntimeException {
  private final String code;

  private ScheduleException(String code) {
    super(code, null, false, false);
    this.code = code;
  }

  public static ScheduleException invalidRequest() {
    return new ScheduleException("INVALID_REQUEST");
  }

  public static ScheduleException tripNotFound() {
    return new ScheduleException("TRIP_NOT_FOUND");
  }

  public static ScheduleException versionNotFound() {
    return new ScheduleException("SCHEDULE_VERSION_NOT_FOUND");
  }

  public static ScheduleException dataUnavailable() {
    return new ScheduleException("TRIP_DATA_UNAVAILABLE");
  }

  public String code() {
    return code;
  }
}
