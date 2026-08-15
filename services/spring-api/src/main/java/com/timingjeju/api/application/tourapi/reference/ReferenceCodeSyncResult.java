package com.timingjeju.api.application.tourapi.reference;

import java.util.UUID;

public record ReferenceCodeSyncResult(
    UUID runId, UUID snapshotId, int inserted, int updated, int skipped, boolean replayed) {

  public static ReferenceCodeSyncResult completed(
      UUID runId, UUID snapshotId, int inserted, int updated, int skipped) {
    return new ReferenceCodeSyncResult(runId, snapshotId, inserted, updated, skipped, false);
  }

  public static ReferenceCodeSyncResult replayed(UUID runId) {
    return new ReferenceCodeSyncResult(runId, null, 0, 0, 0, true);
  }
}
