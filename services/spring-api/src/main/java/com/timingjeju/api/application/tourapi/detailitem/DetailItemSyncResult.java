package com.timingjeju.api.application.tourapi.detailitem;

public record DetailItemSyncResult(
    int insertedCount, int updatedCount, int skippedCount, int staledCount, int tombstonedCount) {
  public DetailItemSyncResult {
    if (insertedCount < 0
        || updatedCount < 0
        || skippedCount < 0
        || staledCount < 0
        || tombstonedCount < 0) {
      throw new IllegalArgumentException("저장 결과 count는 음수일 수 없습니다.");
    }
  }
}
