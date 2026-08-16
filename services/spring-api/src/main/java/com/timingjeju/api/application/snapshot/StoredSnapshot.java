package com.timingjeju.api.application.snapshot;

import java.time.Instant;
import java.util.UUID;

public record StoredSnapshot(
    UUID snapshotId,
    UUID importRunId,
    SnapshotScope scope,
    String externalRecordId,
    String requestHash,
    String pageKey,
    Integer httpStatus,
    String providerResultCode,
    Instant fetchedAt,
    Instant sourceModifiedAt,
    Instant expiresAt,
    String parserVersion,
    String payloadHash,
    SnapshotPayloadFormat payloadFormat,
    SnapshotStatus initialStatus,
    String initialErrorCode,
    SnapshotStatus status,
    String errorCode,
    String errorMessage,
    String requestMetadataRedactedJson,
    String rawPayloadJson,
    long payloadSizeBytes,
    String redactionVersion,
    Instant purgeAfter) {

  public SnapshotSaveResult result(boolean replayed) {
    return new SnapshotSaveResult(
        snapshotId, requestHash, payloadHash, replayed, fetchedAt, status);
  }

  public StoredSnapshot withSnapshotId(UUID changedId) {
    return new StoredSnapshot(
        changedId,
        importRunId,
        scope,
        externalRecordId,
        requestHash,
        pageKey,
        httpStatus,
        providerResultCode,
        fetchedAt,
        sourceModifiedAt,
        expiresAt,
        parserVersion,
        payloadHash,
        payloadFormat,
        initialStatus,
        initialErrorCode,
        status,
        errorCode,
        errorMessage,
        requestMetadataRedactedJson,
        rawPayloadJson,
        payloadSizeBytes,
        redactionVersion,
        purgeAfter);
  }

  public StoredSnapshot withRawPayloadJson(String changedPayload) {
    return new StoredSnapshot(
        snapshotId,
        importRunId,
        scope,
        externalRecordId,
        requestHash,
        pageKey,
        httpStatus,
        providerResultCode,
        fetchedAt,
        sourceModifiedAt,
        expiresAt,
        parserVersion,
        payloadHash,
        payloadFormat,
        initialStatus,
        initialErrorCode,
        status,
        errorCode,
        errorMessage,
        requestMetadataRedactedJson,
        changedPayload,
        payloadSizeBytes,
        redactionVersion,
        purgeAfter);
  }

  @Override
  public String toString() {
    return "StoredSnapshot[snapshotId="
        + snapshotId
        + ", importRunId="
        + importRunId
        + ", scope="
        + scope
        + ", pageKey="
        + pageKey
        + ", status="
        + status
        + ", payloadSizeBytes="
        + payloadSizeBytes
        + ", redactionVersion="
        + redactionVersion
        + "]";
  }
}
