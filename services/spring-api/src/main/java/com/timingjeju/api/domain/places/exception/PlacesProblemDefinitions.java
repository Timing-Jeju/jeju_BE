package com.timingjeju.api.domain.places.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class PlacesProblemDefinitions implements ProblemDefinitionContributor {

  @Override
  public List<ProblemDefinition> definitions() {
    return List.of(
        problem("INVALID_QUERY_PARAMETER", "요청 검색 조건이 올바르지 않습니다", 400, "검색 조건의 형식과 범위를 확인해 주세요."),
        problem(
            "INVALID_GEO_FILTER",
            "요청 위치 조건이 올바르지 않습니다",
            400,
            "위도와 경도는 함께 입력하고 제주 범위와 반경을 확인해 주세요."),
        problem(
            "CURSOR_CONTEXT_MISMATCH",
            "커서의 검색 조건이 일치하지 않습니다",
            400,
            "현재 검색 조건으로 목록을 처음부터 다시 조회해 주세요."),
        problem("INVALID_CURSOR", "커서가 유효하지 않습니다", 400, "목록을 처음부터 다시 조회해 주세요."),
        problem("PLACE_QUERY_CONSTRAINT_VIOLATION", "검색 조건을 처리할 수 없습니다", 422, "검색 조건 조합을 변경해 주세요."),
        problem("INVALID_ACCESS_TOKEN", "인증에 실패했습니다", 401, "인증 토큰이 유효하지 않습니다."),
        problem("AUTHENTICATION_REQUIRED", "인증이 필요합니다", 401, "저장한 장소만 조회하려면 로그인해 주세요."),
        problem("PLACE_DATA_UNAVAILABLE", "장소 데이터를 사용할 수 없습니다", 503, "잠시 후 다시 시도해 주세요."));
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
