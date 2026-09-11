package com.timingjeju.api.global.timetable;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.timetable.TimetableImportResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TimetableImportReportFormatterTest {
  @Test
  void rejected와_omitted의_sheet_row_column을_결정적이고_제한된_상세로_출력한다() {
    List<String> rejected = new ArrayList<>();
    for (int index = TimetableImportReportFormatter.MAX_DETAILS + 2; index >= 1; index--) {
      rejected.add("INVALID_TIME sheet=sheet-b row=" + index + " column=3");
    }
    String longOmission =
        "sheet-a/row=8/column=4/"
            + "x".repeat(TimetableImportReportFormatter.MAX_DETAIL_CHARS + 20);
    var result = new TimetableImportResult(7, rejected, true, false, null, List.of(longOmission));

    String report = TimetableImportReportFormatter.format("101", true, result);

    assertThat(report)
        .contains("scheduleId=101", "accepted=7", "rejected=22", "omitted=1")
        .contains("rejectedDetails=[INVALID_TIME sheet=sheet-b row=1 column=3")
        .contains("rejectedTruncated=2")
        .contains("omittedDetails=[sheet-a/row=8/column=4/")
        .doesNotContain("\n", "\r")
        .hasSizeLessThan(8_000);
  }

  @Test
  void 동일한_상세_set은_입력순서와_무관하게_같은_report다() {
    List<String> first =
        List.of("INVALID_TIME sheet=b row=8 column=4", "INVALID_TIME sheet=a row=9 column=2");
    List<String> second = List.of(first.get(1), first.get(0));

    String firstReport =
        TimetableImportReportFormatter.format(
            "201", true, new TimetableImportResult(0, first, true, false, null));
    String secondReport =
        TimetableImportReportFormatter.format(
            "201", true, new TimetableImportResult(0, second, true, false, null));

    assertThat(firstReport).isEqualTo(secondReport);
  }
}
