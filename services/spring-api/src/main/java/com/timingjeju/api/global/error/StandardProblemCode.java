package com.timingjeju.api.global.error;

import java.util.Arrays;
import java.util.List;

public enum StandardProblemCode {
  VALIDATION_FAILED(400, "요청 값이 올바르지 않습니다.", "입력값을 확인해 주세요."),
  IDEMPOTENCY_KEY_REQUIRED(400, "멱등성 키가 필요합니다.", "Idempotency-Key 헤더를 입력해 주세요."),
  IDEMPOTENCY_KEY_INVALID(400, "멱등성 키가 유효하지 않습니다.", "UUID 형식의 Idempotency-Key를 입력해 주세요."),
  CURSOR_INVALID(400, "커서가 유효하지 않습니다.", "목록을 처음부터 다시 조회해 주세요."),
  AUTHENTICATION_REQUIRED(
      "https://api.timing-jeju.com/problems/authentication-required",
      401,
      "인증이 필요합니다",
      "로그인 후 다시 요청해 주세요."),
  INVALID_ACCESS_TOKEN(
      "https://api.timing-jeju.com/problems/invalid-access-token",
      401,
      "인증 정보가 올바르지 않습니다",
      "유효한 인증 정보로 다시 요청해 주세요."),
  AUTH_TOKEN_INVALID(401, "인증에 실패했습니다.", "인증 토큰이 유효하지 않습니다."),
  AUTH_ACCESS_DENIED(403, "접근이 거부되었습니다.", "접근 권한이 없습니다."),
  RESOURCE_NOT_FOUND(404, "요청한 리소스를 찾을 수 없습니다.", "요청한 리소스가 존재하지 않습니다."),
  METHOD_NOT_ALLOWED(405, "허용되지 않은 요청 방식입니다.", "지원되는 HTTP 메서드로 요청해 주세요."),
  NOT_ACCEPTABLE(406, "요청한 응답 형식을 제공할 수 없습니다.", "지원되는 Accept 형식으로 요청해 주세요."),
  CONFLICT(409, "요청이 현재 상태와 충돌합니다.", "최신 상태를 확인한 뒤 다시 시도해 주세요."),
  IDEMPOTENCY_KEY_REUSED(409, "멱등성 키를 재사용할 수 없습니다.", "새 Idempotency-Key로 다시 요청해 주세요."),
  UNSUPPORTED_MEDIA_TYPE(415, "지원하지 않는 미디어 형식입니다.", "지원되는 Content-Type으로 요청해 주세요."),
  UNPROCESSABLE_ENTITY(422, "요청 내용을 처리할 수 없습니다.", "요청 내용을 확인해 주세요."),
  FAILED_DEPENDENCY(424, "선행 요청을 완료할 수 없습니다.", "잠시 후 다시 시도해 주세요."),
  TOO_MANY_REQUESTS(429, "요청이 너무 많습니다.", "잠시 후 다시 시도해 주세요."),
  UPSTREAM_ERROR(502, "외부 서비스 요청을 완료하지 못했습니다.", "외부 서비스 응답을 처리할 수 없습니다."),
  SERVICE_UNAVAILABLE(503, "서비스를 일시적으로 사용할 수 없습니다.", "잠시 후 다시 시도해 주세요."),
  UPSTREAM_TIMEOUT(504, "외부 서비스 응답이 지연되고 있습니다.", "잠시 후 다시 시도해 주세요."),
  AUTH_INTERNAL_ERROR(500, "내부 서버 오류가 발생했습니다.", "인증 처리 중 내부 오류가 발생했습니다."),
  INTERNAL_SERVER_ERROR(500, "내부 서버 오류가 발생했습니다.", "요청을 처리하는 중 내부 오류가 발생했습니다.");

  private final ProblemDefinition definition;

  StandardProblemCode(int status, String title, String detail) {
    String code = name();
    this.definition = ProblemDefinition.forCode(code, title, status, detail);
  }

  StandardProblemCode(String type, int status, String title, String detail) {
    this.definition =
        new ProblemDefinition(java.net.URI.create(type), title, status, name(), detail);
  }

  public ProblemDefinition definition() {
    return definition;
  }

  static List<ProblemDefinition> definitions() {
    return Arrays.stream(values()).map(StandardProblemCode::definition).toList();
  }
}
