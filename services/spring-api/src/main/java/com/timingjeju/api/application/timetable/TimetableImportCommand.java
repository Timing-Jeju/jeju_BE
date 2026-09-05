package com.timingjeju.api.application.timetable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record TimetableImportCommand(
    byte[] xlsx,
    String scheduleId,
    LocalDate effectiveDate,
    Instant fetchedAt,
    boolean dryRun,
    String idempotencyKey) {
  public TimetableImportCommand {
    xlsx = Objects.requireNonNull(xlsx).clone();
    Objects.requireNonNull(scheduleId);
    Objects.requireNonNull(effectiveDate);
    Objects.requireNonNull(fetchedAt);
    Objects.requireNonNull(idempotencyKey);
  }

  @Override
  public byte[] xlsx() {
    return xlsx.clone();
  }

  @Override
  public String toString() {
    return "TimetableImportCommand[scheduleId="
        + scheduleId
        + ",bytes="
        + xlsx.length
        + ",dryRun="
        + dryRun
        + "]";
  }
}
