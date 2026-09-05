package com.timingjeju.api.application.timetable;

import java.util.List;

public record TimetableImportResult(
    int acceptedRows,
    List<String> rejectedRows,
    boolean dryRun,
    boolean replayed,
    TimetableWriteResult writes,
    List<String> omissions) {
  public TimetableImportResult(
      int acceptedRows,
      List<String> rejectedRows,
      boolean dryRun,
      boolean replayed,
      TimetableWriteResult writes) {
    this(acceptedRows, rejectedRows, dryRun, replayed, writes, List.of());
  }

  public TimetableImportResult {
    rejectedRows = List.copyOf(rejectedRows);
    omissions = List.copyOf(omissions);
  }
}
