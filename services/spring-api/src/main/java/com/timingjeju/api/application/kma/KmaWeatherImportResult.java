package com.timingjeju.api.application.kma;

import com.timingjeju.api.application.importing.ImportRunCounts;
import java.util.Objects;
import java.util.UUID;

public record KmaWeatherImportResult(
    UUID runId,
    ImportRunCounts counts,
    long checkpointVersion,
    KmaWeatherFreshness freshness,
    boolean replayed) {
  public KmaWeatherImportResult {
    Objects.requireNonNull(runId, "runId는 필수입니다.");
    Objects.requireNonNull(counts, "counts는 필수입니다.");
    Objects.requireNonNull(freshness, "freshness는 필수입니다.");
  }
}
