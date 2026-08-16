package com.timingjeju.api.application.kma;

import com.timingjeju.api.application.importing.ImportRunCounts;
import java.util.Objects;

public record KmaWeatherCommitResult(ImportRunCounts counts, long checkpointVersion) {
  public KmaWeatherCommitResult {
    Objects.requireNonNull(counts, "counts는 필수입니다.");
    if (checkpointVersion < 0) {
      throw new IllegalArgumentException("checkpointVersion은 음수일 수 없습니다.");
    }
  }
}
