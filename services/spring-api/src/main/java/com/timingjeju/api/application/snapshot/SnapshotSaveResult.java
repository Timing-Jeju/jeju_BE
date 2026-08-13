package com.timingjeju.api.application.snapshot;

import java.util.UUID;

public record SnapshotSaveResult(
    UUID snapshotId, String requestFingerprint, String payloadHash, boolean replayed) {}
