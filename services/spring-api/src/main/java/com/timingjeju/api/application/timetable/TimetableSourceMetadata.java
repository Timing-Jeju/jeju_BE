package com.timingjeju.api.application.timetable;

public record TimetableSourceMetadata(
    String providerName,
    String providerCode,
    String datasetId,
    String uddi,
    String canonicalUrl,
    String license,
    String licenseCheckedAt) {
  public static TimetableSourceMetadata official(String scheduleId) {
    return new TimetableSourceMetadata(
        "제주특별자치도",
        "JEJU_PROVINCE",
        "3043887",
        "uddi:3e6924f3-f090-495a-bc59-ad7e159d78f2",
        "https://bus.jeju.go.kr/data/schedule/downScheduleExcel?gscheduleId=" + scheduleId,
        "제한 없음",
        "2026-09-04");
  }
}
