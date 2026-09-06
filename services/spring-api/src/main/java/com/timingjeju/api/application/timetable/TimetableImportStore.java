package com.timingjeju.api.application.timetable;

import java.time.LocalDate;

public interface TimetableImportStore {
  TimetableVersionState inspect(
      String scheduleId, LocalDate effectiveDate, String importFingerprint);

  TimetableWriteResult commit(TimetableAtomicWrite write);
}
