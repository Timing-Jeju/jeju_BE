package com.timingjeju.api.application.generation;

/** 저장된 코드만 닫힌 공개 안내로 변환한다. provider/DB 오류 message는 받지 않는다. */
public record GenerationFailure(String code, String detail, boolean retryable) {
  public static GenerationFailure from(String status, String storedCode) {
    if ("cancelled".equals(status))
      return new GenerationFailure("ASYNC_RUN_CANCELLED", "일정 생성이 취소되었습니다.", false);
    if (!"failed".equals(status)) return null;
    return switch (storedCode == null ? "" : storedCode) {
      case "MCP_TIMEOUT", "ASYNC_RUN_DEADLINE_EXCEEDED" ->
          new GenerationFailure(storedCode, "일정 계산 시간이 초과되었습니다. 다시 시도해 주세요.", true);
      case "MCP_TRANSPORT_UNAVAILABLE", "MCP_NOT_READY", "MCP_GENERATION_DISABLED" ->
          new GenerationFailure(storedCode, "일정 계산 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.", true);
      case "GENERATION_INPUT_CONSTRAINT_VIOLATION" ->
          new GenerationFailure(storedCode, "저장된 여행 조건을 확인한 뒤 다시 생성해 주세요.", false);
      case "GENERATION_INPUT_UNAVAILABLE" ->
          new GenerationFailure(storedCode, "저장된 생성 입력을 사용할 수 없습니다. 여행 조건을 다시 확인해 주세요.", false);
      case "MCP_CONTRACT_INVALID", "MCP_PROTOCOL_INVALID" ->
          new GenerationFailure(storedCode, "일정 계산 결과를 검증할 수 없습니다.", false);
      case "MCP_AUTHENTICATION_FAILED" ->
          new GenerationFailure(storedCode, "일정 계산 서비스 인증을 확인할 수 없습니다.", false);
      case "GENERATION_RETRY_EXHAUSTED" ->
          new GenerationFailure(storedCode, "일정 생성 재시도 한도에 도달했습니다. 잠시 후 다시 생성해 주세요.", true);
      default -> new GenerationFailure("GENERATION_EXECUTION_FAILED", "일정 생성을 완료하지 못했습니다.", false);
    };
  }
}
