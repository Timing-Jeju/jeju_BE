package com.timingjeju.api.application.tourapi.place;

public record PlaceListUpsertResult(int inserted, int updated, int skipped) {
  public PlaceListUpsertResult {
    if (inserted < 0 || updated < 0 || skipped < 0) {
      throw new IllegalArgumentException("upsert count는 음수일 수 없습니다.");
    }
  }
}
