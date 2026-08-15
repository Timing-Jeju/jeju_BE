package com.timingjeju.api.application.tourapi.detail;

public record PlaceDetailUpsertResult(boolean inserted, boolean updated, boolean skipped) {
  public PlaceDetailUpsertResult {
    if ((inserted ? 1 : 0) + (updated ? 1 : 0) + (skipped ? 1 : 0) != 1) {
      throw new IllegalArgumentException("정확히 하나의 저장 결과가 필요합니다.");
    }
  }

  public static PlaceDetailUpsertResult insertedResult() {
    return new PlaceDetailUpsertResult(true, false, false);
  }

  public static PlaceDetailUpsertResult updatedResult() {
    return new PlaceDetailUpsertResult(false, true, false);
  }

  public static PlaceDetailUpsertResult skippedResult() {
    return new PlaceDetailUpsertResult(false, false, true);
  }
}
