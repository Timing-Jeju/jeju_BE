package com.timingjeju.api.application.schedule;

import java.util.Objects;

public record ScheduleLookup(Status status, ScheduleSnapshot schedule) {
  public ScheduleLookup {
    Objects.requireNonNull(status);
    if ((status == Status.FOUND) != (schedule != null)) {
      throw new IllegalArgumentException("발견 상태와 일정 값이 일치해야 합니다.");
    }
  }

  public static ScheduleLookup found(ScheduleSnapshot schedule) {
    return new ScheduleLookup(Status.FOUND, Objects.requireNonNull(schedule));
  }

  public static ScheduleLookup tripNotFound() {
    return new ScheduleLookup(Status.TRIP_NOT_FOUND, null);
  }

  public static ScheduleLookup versionNotFound() {
    return new ScheduleLookup(Status.VERSION_NOT_FOUND, null);
  }

  public enum Status {
    FOUND,
    TRIP_NOT_FOUND,
    VERSION_NOT_FOUND
  }
}
