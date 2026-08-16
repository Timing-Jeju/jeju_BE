package com.timingjeju.api.application.tourapi.sync;

public final class IncrementalSyncException extends RuntimeException {

  private IncrementalSyncException() {
    super("TOUR_API 증분 응답 계약이 올바르지 않습니다.", null, false, false);
  }

  public static IncrementalSyncException invalidResponse() {
    return new IncrementalSyncException();
  }
}
