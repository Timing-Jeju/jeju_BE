package com.timingjeju.api.application.kma;

import java.util.Objects;
import java.util.UUID;

public record KmaWeatherImportCommand(UUID gridPointId, int nx, int ny, String idempotencyKey) {
  public KmaWeatherImportCommand {
    Objects.requireNonNull(gridPointId, "gridPointId는 필수입니다.");
    if (nx < 1 || nx > 149 || ny < 1 || ny > 253) {
      throw new IllegalArgumentException("KMA DFS 격자 범위가 올바르지 않습니다.");
    }
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey는 필수입니다.");
    }
    idempotencyKey = idempotencyKey.strip();
  }
}
