package com.timingjeju.api.application.tago.route;

import java.time.Instant;
import java.util.UUID;

public record TagoRouteWrite(
    TagoRoute route, UUID snapshotId, UUID importRunId, Instant observedAt) {}
