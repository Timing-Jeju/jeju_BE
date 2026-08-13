package com.timingjeju.api.application.snapshot;

public enum SnapshotStoreError {
  PAYLOAD_TOO_LARGE("SNAPSHOT_PAYLOAD_TOO_LARGE", "원천 응답 크기가 허용 범위를 초과했습니다."),
  UNSUPPORTED_CHARSET("SNAPSHOT_UNSUPPORTED_CHARSET", "원천 응답 문자 인코딩을 지원하지 않습니다."),
  SCOPE_MISMATCH("SNAPSHOT_SCOPE_MISMATCH", "수집 실행과 원천 응답 범위가 일치하지 않습니다."),
  INVALID_TRANSITION("SNAPSHOT_INVALID_TRANSITION", "원천 응답 상태를 변경할 수 없습니다."),
  NOT_FOUND("SNAPSHOT_NOT_FOUND", "원천 응답 기록을 찾을 수 없습니다."),
  HASH_COLLISION("SNAPSHOT_HASH_COLLISION", "원천 응답 무결성을 확인할 수 없습니다."),
  INVALID_REQUEST("SNAPSHOT_INVALID_REQUEST", "원천 응답을 저장할 수 없습니다.");

  private final String code;
  private final String message;

  SnapshotStoreError(String code, String message) {
    this.code = code;
    this.message = message;
  }

  public String code() {
    return code;
  }

  public String message() {
    return message;
  }
}
