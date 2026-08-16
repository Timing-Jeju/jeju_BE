package com.timingjeju.api.application.tourapi.image;

public record PlaceImageSyncResult(
    int insertedCount, int updatedCount, int skippedCount, int staledCount, int tombstonedCount) {
  public PlaceImageSyncResult {
    if (insertedCount < 0
        || updatedCount < 0
        || skippedCount < 0
        || staledCount < 0
        || tombstonedCount < 0) {
      throw new IllegalArgumentException("image sync count는 음수일 수 없습니다.");
    }
  }
}
