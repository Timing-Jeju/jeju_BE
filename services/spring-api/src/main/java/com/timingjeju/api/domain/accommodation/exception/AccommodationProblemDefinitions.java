package com.timingjeju.api.domain.accommodation.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class AccommodationProblemDefinitions implements ProblemDefinitionContributor {
  private static final List<ProblemDefinition> ALL =
      List.of(
          problem(
              "INVALID_REQUEST",
              "요청 값이 올바르지 않습니다",
              400,
              "필수값, 형식, XOR, Idempotency-Key와 If-Match를 확인해 주세요."),
          problem("TRIP_NOT_FOUND", "여행을 찾을 수 없습니다", 404, "요청한 여행이 없거나 접근할 수 없습니다."),
          problem("ACCOMMODATION_NOT_FOUND", "숙소를 찾을 수 없습니다", 404, "요청한 숙소가 없거나 해당 여행에 속하지 않습니다."),
          problem("PLACE_NOT_FOUND", "장소를 찾을 수 없습니다", 404, "요청한 숙소 장소가 없거나 사용할 수 없습니다."),
          problem(
              "IDEMPOTENCY_KEY_REUSED",
              "멱등성 키가 다른 요청에 사용되었습니다",
              409,
              "새 Idempotency-Key로 다시 요청해 주세요."),
          problem(
              "TRIP_VERSION_CONFLICT", "여행 조건이 이미 변경되었습니다", 409, "최신 여행과 ETag를 조회한 뒤 다시 요청해 주세요."),
          problem(
              "ACCOMMODATION_CONCURRENT_CONFLICT",
              "숙소가 동시에 변경되었습니다",
              409,
              "최신 숙소 순서와 기간을 조회한 뒤 다시 요청해 주세요."),
          problem(
              "ACCOMMODATION_DATE_GAP_OR_OVERLAP",
              "숙소 날짜를 적용할 수 없습니다",
              422,
              "여행 기간 안에서 숙소 날짜의 공백과 중복 없이 순서를 확인해 주세요."),
          problem(
              "ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE",
              "활성 일정에서 사용하는 숙소는 삭제할 수 없습니다",
              422,
              "일정을 재생성하거나 활성 일정을 해제한 뒤 숙소를 삭제해 주세요."),
          problem("ACCOMMODATION_DATA_UNAVAILABLE", "숙소 데이터를 사용할 수 없습니다", 503, "잠시 후 다시 시도해 주세요."));

  @Override
  public List<ProblemDefinition> definitions() {
    return ALL.stream()
        .filter(
            definition ->
                java.util.Set.of(
                        "ACCOMMODATION_NOT_FOUND",
                        "ACCOMMODATION_CONCURRENT_CONFLICT",
                        "ACCOMMODATION_DATE_GAP_OR_OVERLAP",
                        "ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE",
                        "ACCOMMODATION_DATA_UNAVAILABLE")
                    .contains(definition.code()))
        .toList();
  }

  public ProblemDefinition find(String code) {
    return ALL.stream()
        .filter(definition -> definition.code().equals(code))
        .findFirst()
        .orElseGet(
            () ->
                problem(
                    "ACCOMMODATION_DATA_UNAVAILABLE",
                    "숙소 데이터를 사용할 수 없습니다",
                    503,
                    "잠시 후 다시 시도해 주세요."));
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
