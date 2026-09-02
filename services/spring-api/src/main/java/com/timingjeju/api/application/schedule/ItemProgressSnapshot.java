package com.timingjeju.api.application.schedule;

import java.time.Instant;

public record ItemProgressSnapshot(
    String status,
    Instant actualStartedAt,
    Instant actualArrivedAt,
    Instant actualCompletedAt,
    Instant updatedAt) {}
