package com.timingjeju.api.global.timetable;

import com.timingjeju.api.application.timetable.TimetableImportResult;
import java.util.List;

final class TimetableImportReportFormatter {
  static final int MAX_DETAILS = 20;
  static final int MAX_DETAIL_CHARS = 160;

  private TimetableImportReportFormatter() {}

  static String format(String scheduleId, boolean dryRun, TimetableImportResult result) {
    return "Jeju timetable import completed: scheduleId="
        + sanitize(scheduleId)
        + ", dryRun="
        + dryRun
        + ", accepted="
        + result.acceptedRows()
        + ", rejected="
        + result.rejectedRows().size()
        + ", omitted="
        + result.omissions().size()
        + ", replayed="
        + result.replayed()
        + details("rejected", result.rejectedRows())
        + details("omitted", result.omissions());
  }

  private static String details(String label, List<String> values) {
    if (values.isEmpty()) return "";
    List<String> bounded =
        values.stream()
            .map(TimetableImportReportFormatter::sanitize)
            .sorted()
            .limit(MAX_DETAILS)
            .toList();
    int truncated = values.size() - bounded.size();
    return ", "
        + label
        + "Details="
        + bounded
        + (truncated == 0 ? "" : ", " + label + "Truncated=" + truncated);
  }

  private static String sanitize(String value) {
    if (value == null) return "<null>";
    String singleLine = value.replace('\n', ' ').replace('\r', ' ');
    return singleLine.length() <= MAX_DETAIL_CHARS
        ? singleLine
        : singleLine.substring(0, MAX_DETAIL_CHARS) + "...";
  }
}
