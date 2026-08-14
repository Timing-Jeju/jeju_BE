package com.timingjeju.api.application.snapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record SnapshotStateMutation(
    UUID snapshotId,
    SnapshotStatus status,
    Instant transitionedAt,
    Duration retention,
    String errorCode,
    String errorMessage) {}
