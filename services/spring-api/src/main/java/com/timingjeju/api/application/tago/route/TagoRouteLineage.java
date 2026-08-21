package com.timingjeju.api.application.tago.route;

import java.time.Instant;
import java.util.UUID;

public record TagoRouteLineage(
    String kind,
    String routeId,
    int pageNo,
    int rawItemCount,
    UUID snapshotId,
    String payloadHash,
    Instant fetchedAt) {}
