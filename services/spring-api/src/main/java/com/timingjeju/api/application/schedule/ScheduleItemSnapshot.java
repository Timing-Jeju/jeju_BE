package com.timingjeju.api.application.schedule;

import java.time.Instant;
import java.util.UUID;

public record ScheduleItemSnapshot(
    UUID itemId,
    int sequenceNo,
    String itemType,
    UUID placeId,
    String title,
    Instant plannedStartAt,
    Instant plannedEndAt,
    int stayMinutes,
    int bufferAfterMinutes,
    boolean required,
    String memo,
    ItemProgressSnapshot progress) {}
