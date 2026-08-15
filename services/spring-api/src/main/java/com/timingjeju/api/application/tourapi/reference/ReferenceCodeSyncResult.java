package com.timingjeju.api.application.tourapi.reference;

import java.util.UUID;

public record ReferenceCodeSyncResult(
    UUID runId, UUID snapshotId, int inserted, int updated, int skipped) {}
