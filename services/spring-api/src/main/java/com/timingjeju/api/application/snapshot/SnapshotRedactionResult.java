package com.timingjeju.api.application.snapshot;

public record SnapshotRedactionResult(
    String rawPayloadJson,
    String requestMetadataJson,
    SnapshotStatus initialStatus,
    String errorCode) {}
