package com.timingjeju.api.global.timetable;

import com.timingjeju.api.application.timetable.OperatorTimetableMapping;
import com.timingjeju.api.application.timetable.ParsedTimetable;
import com.timingjeju.api.application.timetable.TimetableImportCommand;
import com.timingjeju.api.application.timetable.TimetableParseException;
import com.timingjeju.api.application.timetable.TimetableParser;
import java.util.Map;

public final class MappedTimetableParser implements TimetableParser {
  private final JejuTimetableXlsxParser parser;
  private final Map<String, OperatorTimetableMapping> mappings;

  public MappedTimetableParser(
      JejuTimetableXlsxParser parser, Map<String, OperatorTimetableMapping> mappings) {
    this.parser = parser;
    this.mappings = Map.copyOf(mappings);
  }

  @Override
  public ParsedTimetable parse(TimetableImportCommand command) {
    OperatorTimetableMapping mapping = mappings.get(command.scheduleId());
    if (mapping == null || !mapping.scheduleId().equals(command.scheduleId())) {
      throw new TimetableParseException("SCHEDULE_ALLOWLIST_MISMATCH", "sheet=<workbook>");
    }
    return parser.parse(command.xlsx(), mapping, command.effectiveDate(), command.dryRun());
  }
}
