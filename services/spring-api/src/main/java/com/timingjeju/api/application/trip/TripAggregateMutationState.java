package com.timingjeju.api.application.trip;

import java.time.LocalDate;
import java.util.UUID;

public record TripAggregateMutationState(
    String status,
    LocalDate startDate,
    LocalDate endDate,
    String timezone,
    UUID activeScheduleVersionId,
    long revision,
    boolean hasScheduleVersion) {}
