package com.timingjeju.api.application.timetable;

import java.util.List;

@FunctionalInterface
public interface TimetableCatalog {
  TimetableCatalogValidation validate(List<TimetableEntryCandidate> entries);
}
