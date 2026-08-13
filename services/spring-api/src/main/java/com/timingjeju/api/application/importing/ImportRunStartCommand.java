package com.timingjeju.api.application.importing;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ImportRunStartCommand(
    ImportSourceKind sourceKind,
    String sourceName,
    ImportRunScope scope,
    String dataVersion,
    String parserVersion,
    String schemaVersion,
    ImportSyncMode syncMode,
    String requestFingerprint,
    String idempotencyKey,
    UUID rawParentRunId) {

  public ImportRunStartCommand {
    Objects.requireNonNull(sourceKind, "sourceKind는 필수입니다.");
    sourceName = requireText(sourceName, "sourceName");
    Objects.requireNonNull(scope, "scope는 필수입니다.");
    dataVersion = requireText(dataVersion, "dataVersion");
    parserVersion = requireText(parserVersion, "parserVersion");
    schemaVersion = requireText(schemaVersion, "schemaVersion");
    Objects.requireNonNull(syncMode, "syncMode는 필수입니다.");
    requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
    idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
  }

  public Optional<UUID> parentRunId() {
    return Optional.ofNullable(rawParentRunId);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + "는 필수입니다.");
    }
    return value.trim();
  }
}
