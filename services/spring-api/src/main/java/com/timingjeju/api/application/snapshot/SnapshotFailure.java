package com.timingjeju.api.application.snapshot;

public enum SnapshotFailure {
  PARSE_REJECTED("SNAPSHOT_PARSE_REJECTED", "원천 응답을 안전하게 해석하지 못했습니다.");

  private final String code;
  private final String message;

  SnapshotFailure(String code, String message) {
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
