package com.timingjeju.api.application.timetable;

import java.time.LocalDate;

public interface TimetableImportStore {
  TimetableVersionState inspect(String scheduleId, LocalDate effectiveDate, String sha256);

  TimetableWriteResult commit(TimetableAtomicWrite write);
}
