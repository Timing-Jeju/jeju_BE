package com.timingjeju.api.application.tourapi.reference;

public record ReferenceCodeUpsertResult(int inserted, int updated, int skipped) {
  public ReferenceCodeUpsertResult {
    if (inserted < 0 || updated < 0 || skipped < 0) {
      throw new IllegalArgumentException("upsert count는 음수일 수 없습니다.");
    }
  }
}
