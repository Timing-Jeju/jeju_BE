package com.timingjeju.api.application.demo;

import java.util.UUID;

public record DemoProvenanceRow(
    UUID id,
    String normalizedEntityType,
    UUID normalizedRowId,
    String operationKey,
    String contentTypeId,
    String requestFingerprint,
    UUID sourceSnapshotId,
    UUID importRunId) {}
