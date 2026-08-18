package com.timingjeju.api.application.placestop;

public record PlaceStopLinkBatchResult(int scopes, int upserted, int tombstoned, boolean replayed) {
  public PlaceStopLinkBatchResult {
    if (scopes < 0 || upserted < 0 || tombstoned < 0) {
      throw new IllegalArgumentException("batch 결과 count는 음수일 수 없습니다.");
    }
  }
}
