package com.timingjeju.api.application.tago.stop;

import java.time.Instant;
import java.util.UUID;

public record TagoStopWrite(
    TagoStation station, UUID snapshotId, UUID importRunId, Instant observedAt) {}
