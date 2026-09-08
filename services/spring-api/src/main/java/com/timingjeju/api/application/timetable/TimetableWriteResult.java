package com.timingjeju.api.application.timetable;

public record TimetableWriteResult(int inserted, int updated, int skipped, boolean replayed) {
  public TimetableWriteResult(int inserted, int updated, int skipped) {
    this(inserted, updated, skipped, false);
  }
}
