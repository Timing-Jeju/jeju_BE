package com.timingjeju.api.application.importing;

public enum ImportCheckpointError {
  STALE_VERSION(true, "체크포인트가 이미 변경되었습니다."),
  RUN_NOT_SUCCEEDED(false, "성공한 수집 실행만 체크포인트를 전진시킬 수 있습니다."),
  INVALID_CHECKPOINT(false, "체크포인트 값이 유효하지 않습니다."),
  INVALID_ADVANCE(false, "체크포인트를 요청한 위치로 전진시킬 수 없습니다."),
  STORAGE_FAILURE(false, "체크포인트 저장소를 사용할 수 없습니다.");

  private final boolean retryable;
  private final String detail;

  ImportCheckpointError(boolean retryable, String detail) {
    this.retryable = retryable;
    this.detail = detail;
  }

  public boolean retryable() {
    return retryable;
  }

  public String detail() {
    return detail;
  }
}
