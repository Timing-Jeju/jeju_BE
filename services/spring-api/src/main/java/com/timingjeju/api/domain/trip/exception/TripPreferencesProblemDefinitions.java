package com.timingjeju.api.domain.trip.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class TripPreferencesProblemDefinitions {
  private static final Map<String, ProblemDefinition> DEFINITIONS =
      Map.of(
          "INVALID_REQUEST",
              problem("INVALID_REQUEST", "요청 값이 올바르지 않습니다", 400, "필수값, 형식과 If-Match를 확인해 주세요."),
          "PLACE_NOT_FOUND",
              problem("PLACE_NOT_FOUND", "장소를 찾을 수 없습니다", 404, "요청한 장소가 없거나 사용할 수 없습니다."),
          "TRIP_VERSION_CONFLICT",
              problem(
                  "TRIP_VERSION_CONFLICT",
                  "여행 조건이 이미 변경되었습니다",
                  409,
                  "최신 여행과 ETag를 조회한 뒤 다시 요청해 주세요."),
          "TRIP_TERMINAL_STATE_CONFLICT",
              problem(
                  "TRIP_TERMINAL_STATE_CONFLICT",
                  "종료된 여행은 변경할 수 없습니다",
                  409,
                  "완료, 취소 또는 실패한 여행 조건은 변경할 수 없습니다."));

  public ProblemDefinition find(String code) {
    return DEFINITIONS.get(code);
  }

  private static ProblemDefinition problem(String code, String title, int status, String detail) {
    return new ProblemDefinition(
        URI.create(
            "https://api.timing-jeju.com/problems/"
                + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')),
        title,
        status,
        code,
        detail);
  }
}
