package com.timingjeju.api.application.trip;

import java.util.UUID;

public record TripAggregateMutationCommit<T>(
    T payload,
    long revision,
    String status,
    UUID activeScheduleVersionId,
    String scheduleEffect,
    boolean regenerationRequired,
    String etag) {}
