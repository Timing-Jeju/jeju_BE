package com.timingjeju.api.application.kma;

public record KmaWeatherUpsertResult(int inserted, int updated, int skipped) {
  public KmaWeatherUpsertResult {
    if (inserted < 0 || updated < 0 || skipped < 0) {
      throw new IllegalArgumentException("weather upsert count는 음수일 수 없습니다.");
    }
  }
}
