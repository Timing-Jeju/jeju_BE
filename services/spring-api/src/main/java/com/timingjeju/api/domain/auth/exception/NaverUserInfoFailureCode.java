package com.timingjeju.api.domain.auth.exception;

public enum NaverUserInfoFailureCode {
  PROVIDER_TOKEN_INVALID("SOCIAL_NAVER_TOKEN_INVALID", "네이버 인증 정보를 확인할 수 없습니다."),
  UPSTREAM_UNAUTHORIZED("SOCIAL_NAVER_UPSTREAM_UNAUTHORIZED", "네이버 인증 정보를 확인할 수 없습니다."),
  UPSTREAM_FORBIDDEN("SOCIAL_NAVER_UPSTREAM_FORBIDDEN", "네이버 사용자 정보 접근이 거부되었습니다."),
  UPSTREAM_RATE_LIMITED("SOCIAL_NAVER_UPSTREAM_RATE_LIMITED", "네이버 로그인 서비스를 일시적으로 사용할 수 없습니다."),
  UPSTREAM_UNAVAILABLE("SOCIAL_NAVER_UPSTREAM_UNAVAILABLE", "네이버 로그인 서비스를 일시적으로 사용할 수 없습니다."),
  UPSTREAM_TIMEOUT("SOCIAL_NAVER_UPSTREAM_TIMEOUT", "네이버 로그인 응답 시간이 초과되었습니다."),
  UPSTREAM_MALFORMED_RESPONSE(
      "SOCIAL_NAVER_UPSTREAM_INVALID_RESPONSE", "네이버 사용자 정보 응답을 확인할 수 없습니다."),
  UPSTREAM_RESPONSE_TOO_LARGE(
      "SOCIAL_NAVER_UPSTREAM_RESPONSE_TOO_LARGE", "네이버 사용자 정보 응답을 확인할 수 없습니다."),
  EMAIL_REQUIRED("SOCIAL_NAVER_EMAIL_REQUIRED", "이메일 제공 동의가 필요합니다.");

  private final String externalCode;
  private final String message;

  NaverUserInfoFailureCode(String externalCode, String message) {
    this.externalCode = externalCode;
    this.message = message;
  }

  public String externalCode() {
    return externalCode;
  }

  public String message() {
    return message;
  }
}
