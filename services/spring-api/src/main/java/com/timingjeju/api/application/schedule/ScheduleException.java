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

  public static ScheduleException placeNotFound() {
    return new ScheduleException("PLACE_NOT_FOUND");
  }

  public static ScheduleException accommodationNotFound() {
    return new ScheduleException("ACCOMMODATION_NOT_FOUND");
  }

  public static ScheduleException transportEventNotFound() {
    return new ScheduleException("TRANSPORT_EVENT_NOT_FOUND");
  }

  public static ScheduleException tripVersionConflict() {
    return new ScheduleException("TRIP_VERSION_CONFLICT");
  }

  public static ScheduleException activeVersionConflict() {
    return new ScheduleException("ACTIVE_SCHEDULE_VERSION_CONFLICT");
  }

  public static ScheduleException itemInvalid() {
    return new ScheduleException("SCHEDULE_ITEM_INVALID");
  }

  public static ScheduleException legIncomplete() {
    return new ScheduleException("SCHEDULE_LEG_INCOMPLETE");
  }

  public static ScheduleException internalServerError() {
    return new ScheduleException("INTERNAL_SERVER_ERROR");
  }

  public String code() {
    return code;
  }
}
