package com.timingjeju.api.domain.generation.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import java.net.URI;
import java.util.Locale;

/** 생성 HTTP 응답과 문서가 공유하는 오류 정의. */
public final class GenerationProblemDefinitions {
  private GenerationProblemDefinitions() {}

  public static ProblemDefinition definition(String code) {
    String[] content =
        switch (code) {
          case "INVALID_QUERY_PARAMETER" ->
              new String[] {"400", "조회 조건이 올바르지 않습니다", "query 없이 다시 요청해 주세요."};
          case "REQUEST_BODY_NOT_ALLOWED" ->
              new String[] {"400", "조회 본문을 사용할 수 없습니다", "본문 없이 다시 요청해 주세요."};
          case "ASYNC_RUN_NOT_FOUND" ->
              new String[] {"404", "생성 작업을 찾을 수 없습니다", "요청한 생성 작업을 찾을 수 없습니다."};
          case "CANDIDATE_NOT_FOUND" -> new String[] {"404", "후보를 찾을 수 없습니다", "요청한 후보를 찾을 수 없습니다."};
          case "CANDIDATE_ALREADY_APPLIED" ->
              new String[] {"409", "이미 적용된 후보입니다", "현재 일정을 다시 조회해 주세요."};
          case "CANDIDATE_STALE" ->
              new String[] {"409", "후보의 여행 조건이 변경되었습니다", "최신 조건으로 다시 생성해 주세요."};
          case "CANDIDATE_EXPIRED" ->
              new String[] {"410", "후보 유효기간이 지났습니다", "여행 조건을 확인한 뒤 다시 생성해 주세요."};
          case "CANDIDATE_EVIDENCE_UNAVAILABLE" ->
              new String[] {"410", "후보 근거를 복원할 수 없습니다", "여행 조건을 확인한 뒤 다시 생성해 주세요."};
          case "CANDIDATE_NOT_APPLICABLE" ->
              new String[] {"422", "후보를 적용할 수 없습니다", "현재 여행과 후보 상태를 확인해 주세요."};
          case "ASYNC_RESULT_EXPIRED" ->
              new String[] {"410", "생성 결과 조회 기간이 지났습니다", "여행 조건을 확인한 뒤 다시 생성해 주세요."};
          case "ASYNC_RESULT_TEMPORARILY_UNAVAILABLE" ->
              new String[] {"503", "생성 결과를 조회할 수 없습니다", "잠시 후 같은 작업을 다시 조회해 주세요."};
          case "INVALID_PATH_PARAMETER" ->
              new String[] {"400", "경로 값이 올바르지 않습니다", "요청 경로의 식별자를 올바른 UUID로 입력해 주세요."};
          case "INVALID_ASYNC_RUN_REQUEST" ->
              new String[] {"400", "비동기 요청이 올바르지 않습니다", "요청 필드와 형식을 확인해 주세요."};
          case "IF_MATCH_REQUIRED" ->
              new String[] {"400", "일정 버전 조건이 필요합니다", "최신 여행의 strong ETag를 If-Match에 입력해 주세요."};
          case "IF_MATCH_INVALID" ->
              new String[] {"400", "일정 버전 조건이 올바르지 않습니다", "최신 여행의 strong ETag를 If-Match에 입력해 주세요."};
          case "IDEMPOTENCY_KEY_REQUIRED" ->
              new String[] {"400", "멱등성 키가 필요합니다", "Idempotency-Key 헤더를 입력해 주세요."};
          case "IDEMPOTENCY_KEY_INVALID" ->
              new String[] {
                "400", "멱등성 키가 올바르지 않습니다", "1~128자 printable ASCII Idempotency-Key를 입력해 주세요."
              };
          case "IDEMPOTENCY_KEY_REUSED" ->
              new String[] {"409", "멱등성 키를 다시 사용할 수 없습니다", "새 Idempotency-Key로 다시 요청해 주세요."};
          case "TRIP_NOT_FOUND" -> new String[] {"404", "여행을 찾을 수 없습니다", "요청한 여행을 찾을 수 없습니다."};
          case "TRIP_VERSION_CONFLICT" ->
              new String[] {"409", "여행 버전이 변경되었습니다", "최신 여행을 조회한 뒤 다시 시도해 주세요."};
          case "ACTIVE_SCHEDULE_VERSION_CONFLICT" ->
              new String[] {"409", "일정 버전이 변경되었습니다", "최신 일정을 다시 확인한 뒤 후보를 적용해 주세요."};
          case "ACTIVE_RUN_CONFLICT" ->
              new String[] {"409", "진행 중인 계산이 있습니다", "기존 계산이 끝난 뒤 다시 요청해 주세요."};
          case "GENERATION_INPUT_CONSTRAINT_VIOLATION" ->
              new String[] {"422", "일정 생성 조건을 사용할 수 없습니다", "여행 기간과 생성 조건을 확인해 주세요."};
          case "ASYNC_INTAKE_UNAVAILABLE" ->
              new String[] {"503", "계산 요청을 접수할 수 없습니다", "요청을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요."};
          default -> null;
        };
    return content == null
        ? null
        : new ProblemDefinition(
            URI.create(
                "https://api.timing-jeju.com/problems/"
                    + code.toLowerCase(Locale.ROOT).replace('_', '-')),
            content[1],
            Integer.parseInt(content[0]),
            code,
            content[2]);
  }
}
