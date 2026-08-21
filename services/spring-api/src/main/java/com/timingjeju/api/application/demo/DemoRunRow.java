package com.timingjeju.api.application.demo;

import java.time.Instant;
import java.util.UUID;

public record DemoRunRow(
    UUID id,
    String sourceKind,
    String sourceOperation,
    String status,
    int fetchedCount,
    int insertedCount,
    Instant startedAt) {}
