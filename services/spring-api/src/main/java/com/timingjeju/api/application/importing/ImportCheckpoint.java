package com.timingjeju.api.application.importing;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ImportCheckpoint(
    ImportRunScope scope,
    Map<String, Object> checkpoint,
    Instant sourceWatermarkAt,
    UUID lastSucceededRunId,
    long version,
    Instant updatedAt) {

  public ImportCheckpoint {
    Objects.requireNonNull(scope, "scope는 필수입니다.");
    checkpoint = immutableCheckpoint(checkpoint);
    if (version < 0) {
      throw new IllegalArgumentException("version은 음수일 수 없습니다.");
    }
    Objects.requireNonNull(updatedAt, "updatedAt은 필수입니다.");
  }

  static Map<String, Object> immutableCheckpoint(Map<String, Object> checkpoint) {
    Objects.requireNonNull(checkpoint, "checkpoint는 필수입니다.");
    return Collections.unmodifiableMap(new LinkedHashMap<>(checkpoint));
  }
}
