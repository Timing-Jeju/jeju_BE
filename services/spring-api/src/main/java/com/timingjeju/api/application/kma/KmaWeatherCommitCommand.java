package com.timingjeju.api.application.kma;

import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.domain.weather.ForecastBaseTime;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record KmaWeatherCommitCommand(
    ImportRunLease lease,
    ImportRunScope scope,
    long expectedCheckpointVersion,
    UUID gridPointId,
    ForecastBaseTime base,
    KmaWeatherBatch batch,
    KmaWeatherLineage lineage,
    Instant fetchedAt,
    boolean stale) {
  public KmaWeatherCommitCommand {
    Objects.requireNonNull(lease, "lease는 필수입니다.");
    Objects.requireNonNull(scope, "scope는 필수입니다.");
    if (expectedCheckpointVersion < 0) {
      throw new IllegalArgumentException("expectedCheckpointVersion은 음수일 수 없습니다.");
    }
    Objects.requireNonNull(gridPointId, "gridPointId는 필수입니다.");
    Objects.requireNonNull(base, "base는 필수입니다.");
    Objects.requireNonNull(batch, "batch는 필수입니다.");
    Objects.requireNonNull(lineage, "lineage는 필수입니다.");
    Objects.requireNonNull(fetchedAt, "fetchedAt은 필수입니다.");
  }
}
