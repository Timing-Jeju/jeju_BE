package com.timingjeju.api.domain.transportevent.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class TransportEventProblemDefinitions implements ProblemDefinitionContributor {
  private static final List<ProblemDefinition> ALL =
      List.of(
          problem("INVALID_REQUEST", "요청 값이 올바르지 않습니다", 400, "필수값, 형식과 If-Match를 확인해 주세요."),
          problem("TRIP_NOT_FOUND", "여행을 찾을 수 없습니다", 404, "요청한 여행이 없거나 접근할 수 없습니다."),
          problem("PLACE_NOT_FOUND", "장소를 찾을 수 없습니다", 404, "요청한 장소가 없거나 사용할 수 없습니다."),
          problem(
              "TRANSPORT_EVENT_NOT_FOUND", "교통 이벤트를 찾을 수 없습니다", 404, "삭제할 도착 또는 출발 교통 이벤트가 없습니다."),
          problem(
              "TRIP_VERSION_CONFLICT", "여행 조건이 이미 변경되었습니다", 409, "최신 여행과 ETag를 조회한 뒤 다시 요청해 주세요."),
          problem(
              "TRIP_TERMINAL_STATE_CONFLICT",
              "종료된 여행은 변경할 수 없습니다",
              409,
              "완료, 취소 또는 실패한 여행 조건은 변경할 수 없습니다."),
          problem(
              "TRANSPORT_EVENT_CONSTRAINT_VIOLATION",
              "교통 이벤트를 처리할 수 없습니다",
              422,
              "날짜, +09:00 시간대와 터미널 입력을 확인해 주세요."),
          problem("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다", 500, "잠시 후 다시 시도해 주세요."));

  @Override
  public List<ProblemDefinition> definitions() {
    return ALL.stream()
        .filter(
            definition ->
                java.util.Set.of(
                        "TRANSPORT_EVENT_NOT_FOUND", "TRANSPORT_EVENT_CONSTRAINT_VIOLATION")
                    .contains(definition.code()))
        .toList();
  }

  public ProblemDefinition find(String code) {
    return ALL.stream()
        .filter(definition -> definition.code().equals(code))
        .findFirst()
        .orElseGet(
            () -> problem("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다", 500, "잠시 후 다시 시도해 주세요."));
  }

  private static ProblemDefinition problem(String code, String title, int status, String detail) {
    return new ProblemDefinition(
        URI.create(
            "https://api.timing-jeju.com/problems/"
                + code.toLowerCase(Locale.ROOT).replace('_', '-')),
        title,
        status,
        code,
        detail);
  }
}
