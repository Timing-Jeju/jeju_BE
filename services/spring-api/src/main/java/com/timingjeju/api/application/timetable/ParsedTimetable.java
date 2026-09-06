package com.timingjeju.api.application.timetable;

import java.util.List;

public record ParsedTimetable(
    String sha256,
    String mappingFingerprint,
    String importFingerprint,
    String canonicalManifest,
    List<TimetableEntryCandidate> entries,
    List<String> rejectedRows,
    List<String> omissions) {
  public ParsedTimetable {
    entries = List.copyOf(entries);
    rejectedRows = List.copyOf(rejectedRows);
    omissions = List.copyOf(omissions);
  }
}
