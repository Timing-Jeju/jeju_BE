package com.timingjeju.api.application.importing;

public enum ImportRunFailure {
  PARSE_REJECTED("IMPORT_PARSE_REJECTED", "일부 원천 행을 안전하게 해석하지 못했습니다."),
  PROVIDER_UNAVAILABLE("IMPORT_PROVIDER_UNAVAILABLE", "외부 데이터 공급자를 일시적으로 사용할 수 없습니다."),
  INVALID_PROVIDER_RESPONSE("IMPORT_INVALID_PROVIDER_RESPONSE", "외부 데이터 응답 계약이 올바르지 않습니다."),
  STALE_WRITER("IMPORT_STALE_WRITER", "최신 체크포인트를 다른 수집 실행이 먼저 반영했습니다."),
  CANCELLED("IMPORT_CANCELLED", "데이터 수집 실행이 취소되었습니다.");

  private final String code;
  private final String detail;

  ImportRunFailure(String code, String detail) {
    this.code = code;
    this.detail = detail;
  }

  public String code() {
    return code;
  }

  public String detail() {
    return detail;
  }
}
