package com.timingjeju.api.application.tourapi;

import java.time.Instant;
import java.util.UUID;

public record TourApiProvenance(
    UUID id,
    String normalizedEntityType,
    UUID normalizedRowId,
    String operationKey,
    String contentTypeId,
    String requestFingerprint,
    UUID sourceSnapshotId,
    UUID importRunId,
    Instant createdAt) {}
