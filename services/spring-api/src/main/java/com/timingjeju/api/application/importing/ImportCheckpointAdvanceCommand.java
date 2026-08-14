package com.timingjeju.api.application.importing;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ImportCheckpointAdvanceCommand(
    ImportRunScope scope,
    long expectedVersion,
    Map<String, Object> checkpoint,
    Instant sourceWatermarkAt,
    UUID lastSucceededRunId,
    ImportRunStatus runStatus) {

  public ImportCheckpointAdvanceCommand {
    Objects.requireNonNull(scope, "scope는 필수입니다.");
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion은 음수일 수 없습니다.");
    }
    checkpoint = ImportCheckpoint.immutableCheckpoint(checkpoint);
    Objects.requireNonNull(lastSucceededRunId, "lastSucceededRunId는 필수입니다.");
    Objects.requireNonNull(runStatus, "runStatus는 필수입니다.");
  }
}
