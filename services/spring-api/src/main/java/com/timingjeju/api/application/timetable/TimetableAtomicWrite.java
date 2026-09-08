package com.timingjeju.api.application.timetable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TimetableAtomicWrite(
    String scheduleId,
    LocalDate effectiveDate,
    Instant fetchedAt,
    String idempotencyKey,
    String sha256,
    String mappingFingerprint,
    String importFingerprint,
    String canonicalManifest,
    TimetableSourceMetadata source,
    List<TimetableEntryCandidate> entries,
    byte[] rawXlsx) {
  public TimetableAtomicWrite {
    entries = List.copyOf(entries);
    if (rawXlsx != null) throw new IllegalArgumentException("raw XLSX bytes는 저장할 수 없습니다.");
  }
}
