package com.timingjeju.api.application.timetable;

public final class TimetableParseException extends RuntimeException {
  public TimetableParseException(String code, String location) {
    super(code + (location == null ? "" : " " + location));
  }
}
