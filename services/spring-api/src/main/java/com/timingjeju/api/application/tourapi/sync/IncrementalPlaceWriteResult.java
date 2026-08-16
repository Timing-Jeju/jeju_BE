package com.timingjeju.api.application.tourapi.sync;

public record IncrementalPlaceWriteResult(
    int inserted, int updated, int skipped, int staled, int tombstoned) {
  public IncrementalPlaceWriteResult {
    if (inserted < 0 || updated < 0 || skipped < 0 || staled < 0 || tombstoned < 0) {
      throw new IllegalArgumentException("write count는 음수일 수 없습니다.");
    }
  }
}
