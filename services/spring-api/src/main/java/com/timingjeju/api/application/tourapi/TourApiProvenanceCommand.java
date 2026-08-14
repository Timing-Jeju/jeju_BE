package com.timingjeju.api.application.tourapi;

import java.util.Objects;
import java.util.UUID;

public record TourApiProvenanceCommand(
    String normalizedEntityType,
    UUID normalizedRowId,
    String operationKey,
    String contentTypeId,
    String requestFingerprint,
    UUID sourceSnapshotId,
    UUID importRunId) {

  public TourApiProvenanceCommand {
    normalizedEntityType = nonBlank(normalizedEntityType, "normalizedEntityType");
    normalizedRowId = Objects.requireNonNull(normalizedRowId, "normalizedRowId는 필수입니다.");
    operationKey = nonBlank(operationKey, "operationKey");
    contentTypeId = nullableNonBlank(contentTypeId, "contentTypeId");
    requestFingerprint = nonBlank(requestFingerprint, "requestFingerprint");
    if (!requestFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestFingerprint 형식이 올바르지 않습니다.");
    }
    sourceSnapshotId = Objects.requireNonNull(sourceSnapshotId, "sourceSnapshotId는 필수입니다.");
    importRunId = Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
  }

  private static String nonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + "는 비어 있을 수 없습니다.");
    }
    return value;
  }

  private static String nullableNonBlank(String value, String field) {
    return value == null ? null : nonBlank(value, field);
  }
}
