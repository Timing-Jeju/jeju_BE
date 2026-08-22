package com.timingjeju.api.application.tago.arrival;

public record TagoArrivalCommitResult(int insertedCount) {
  public TagoArrivalCommitResult {
    if (insertedCount < 0) throw new IllegalArgumentException("insertedCount는 음수일 수 없습니다.");
  }
}
