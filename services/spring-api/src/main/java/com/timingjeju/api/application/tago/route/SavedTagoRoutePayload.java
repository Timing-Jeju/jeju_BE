package com.timingjeju.api.application.tago.route;

import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.time.Instant;
import java.util.UUID;

public record SavedTagoRoutePayload(
    TagoRouteSourceResponse storedResponse,
    String kind,
    int pageNo,
    UUID snapshotId,
    String payloadHash,
    Instant fetchedAt,
    boolean replayed,
    SnapshotStatus status) {}
