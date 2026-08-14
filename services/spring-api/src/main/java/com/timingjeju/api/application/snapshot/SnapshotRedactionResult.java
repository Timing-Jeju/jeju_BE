package com.timingjeju.api.application.snapshot;

public record SnapshotRedactionResult(
    String rawPayloadJson,
    String requestMetadataJson,
    String requestFingerprintMetadataJson,
    SnapshotStatus initialStatus,
    String errorCode) {}
