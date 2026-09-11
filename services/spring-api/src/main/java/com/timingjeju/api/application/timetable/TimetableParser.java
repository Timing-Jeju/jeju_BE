package com.timingjeju.api.application.timetable;

@FunctionalInterface
public interface TimetableParser {
  ParsedTimetable parse(TimetableImportCommand command);
}
