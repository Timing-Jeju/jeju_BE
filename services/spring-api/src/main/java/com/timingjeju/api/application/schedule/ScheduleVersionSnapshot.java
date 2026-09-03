package com.timingjeju.api.application.schedule;

import java.util.UUID;

public record ScheduleVersionSnapshot(
    UUID scheduleVersionId,
    int versionNo,
    String status,
    String sourceType,
    UUID baseScheduleVersionId,
    Integer score,
    boolean feasibilityStale) {}
