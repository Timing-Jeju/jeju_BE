package com.timingjeju.api.application.tago.stop;

import java.time.Instant;
import java.util.UUID;

public record TagoStopPageLineage(
    String kind,
    int pageNo,
    int rawItemCount,
    UUID snapshotId,
    String payloadHash,
    Instant fetchedAt) {}
