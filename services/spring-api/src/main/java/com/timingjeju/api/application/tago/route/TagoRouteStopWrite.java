package com.timingjeju.api.application.tago.route;

import java.time.Instant;
import java.util.UUID;

public record TagoRouteStopWrite(
    TagoRouteStop stop,
    String directionKey,
    UUID snapshotId,
    UUID importRunId,
    Instant observedAt) {}
