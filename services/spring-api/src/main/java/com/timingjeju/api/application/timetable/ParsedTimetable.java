package com.timingjeju.api.application.timetable;

import java.util.List;

public record ParsedTimetable(
    String sha256,
    String canonicalManifest,
    List<TimetableEntryCandidate> entries,
    List<String> rejectedRows,
    List<String> omissions) {
  public ParsedTimetable(
      String sha256, String canonicalManifest, List<TimetableEntryCandidate> entries) {
    this(sha256, canonicalManifest, entries, List.of(), List.of());
  }

  public ParsedTimetable(
      String sha256,
      String canonicalManifest,
      List<TimetableEntryCandidate> entries,
      List<String> rejectedRows) {
    this(sha256, canonicalManifest, entries, rejectedRows, List.of());
  }

  public ParsedTimetable {
    entries = List.copyOf(entries);
    rejectedRows = List.copyOf(rejectedRows);
    omissions = List.copyOf(omissions);
  }
}
