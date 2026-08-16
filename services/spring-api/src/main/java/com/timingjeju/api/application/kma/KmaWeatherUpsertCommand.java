package com.timingjeju.api.application.kma;

import java.util.Objects;
import java.util.UUID;

public record KmaWeatherUpsertCommand(
    UUID gridPointId, KmaWeatherBatch batch, KmaWeatherLineage lineage) {
  public KmaWeatherUpsertCommand {
    Objects.requireNonNull(gridPointId, "gridPointId는 필수입니다.");
    Objects.requireNonNull(batch, "batch는 필수입니다.");
    Objects.requireNonNull(lineage, "lineage는 필수입니다.");
  }
}
