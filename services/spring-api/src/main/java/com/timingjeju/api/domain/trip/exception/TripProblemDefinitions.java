package com.timingjeju.api.domain.trip.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class TripProblemDefinitions implements ProblemDefinitionContributor {
  @Override
  public List<ProblemDefinition> definitions() {
    return List.of(
        problem(
            "INVALID_REQUEST", "요청 값이 올바르지 않습니다", 400, "여행 제목, 날짜, timezone과 교통 우선순위를 확인해 주세요."),
        problem("TRIP_NOT_FOUND", "여행을 찾을 수 없습니다", 404, "요청한 여행이 없거나 접근할 수 없습니다."),
        problem(
            "TRIP_CONSTRAINT_VIOLATION",
            "여행 조건을 처리할 수 없습니다",
            422,
            "여행은 1일부터 30일까지이며 날짜와 교통 우선순위가 일관되어야 합니다."),
        problem("TRIP_DATA_UNAVAILABLE", "여행 데이터를 사용할 수 없습니다", 503, "잠시 후 다시 시도해 주세요."));
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
