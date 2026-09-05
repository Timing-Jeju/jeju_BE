package com.timingjeju.api.domain.schedule.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class ScheduleProblemDefinitions implements ProblemDefinitionContributor {
  private static final Map<String, ProblemDefinition> MUTATION_DEFINITIONS =
      Map.ofEntries(
          mutation("INVALID_REQUEST", "요청 값이 올바르지 않습니다", 400, "필수값과 형식, 일정 버전 식별자를 확인해 주세요."),
          mutation("IDEMPOTENCY_KEY_REQUIRED", "멱등성 키가 필요합니다", 400, "Idempotency-Key 헤더를 입력해 주세요."),
          mutation(
              "IDEMPOTENCY_KEY_INVALID",
              "멱등성 키가 유효하지 않습니다",
              400,
              "UUID 형식의 Idempotency-Key를 입력해 주세요."),
          mutation("AUTHENTICATION_REQUIRED", "인증이 필요합니다", 401, "로그인 후 다시 요청해 주세요."),
          mutation("INVALID_ACCESS_TOKEN", "인증 정보가 올바르지 않습니다", 401, "유효한 인증 정보로 다시 요청해 주세요."),
          mutation("TRIP_NOT_FOUND", "여행을 찾을 수 없습니다", 404, "요청한 여행이 없거나 접근할 수 없습니다."),
          mutation("PLACE_NOT_FOUND", "장소를 찾을 수 없습니다", 404, "요청한 장소가 없거나 사용할 수 없습니다."),
          mutation("ACCOMMODATION_NOT_FOUND", "숙소를 찾을 수 없습니다", 404, "요청한 숙소가 없거나 해당 여행에 속하지 않습니다."),
          mutation(
              "TRANSPORT_EVENT_NOT_FOUND",
              "교통 이벤트를 찾을 수 없습니다",
              404,
              "요청한 교통 이벤트가 없거나 해당 여행에 속하지 않습니다."),
          mutation(
              "SCHEDULE_VERSION_NOT_FOUND",
              "일정 버전을 찾을 수 없습니다",
              404,
              "요청한 일정 버전이 없거나 해당 여행에 속하지 않습니다."),
          mutation(
              "IDEMPOTENCY_KEY_REUSED",
              "멱등성 키가 다른 요청에 사용되었습니다",
              409,
              "다른 요청이면 새 Idempotency-Key로 다시 보내고, 동일 요청이 처리 중이면 Retry-After 헤더의 초만큼 기다린 뒤 다시 요청해 주세요."),
          mutation(
              "TRIP_VERSION_CONFLICT", "여행 조건이 이미 변경되었습니다", 409, "최신 여행과 ETag를 조회한 뒤 다시 요청해 주세요."),
          mutation(
              "ACTIVE_SCHEDULE_VERSION_CONFLICT",
              "활성 일정이 이미 변경되었습니다",
              409,
              "최신 활성 일정을 조회한 뒤 다시 편집해 주세요."),
          mutation(
              "SCHEDULE_ITEM_INVALID", "일정 항목을 적용할 수 없습니다", 422, "항목 유형별 필수값과 Day 시간 범위를 확인해 주세요."),
          mutation(
              "SCHEDULE_LEG_INCOMPLETE", "이동 구간을 완성할 수 없습니다", 422, "인접 일정 항목 사이의 이동 구간을 확인해 주세요."));

  public static ProblemDefinition mutationDefinition(String code) {
    return MUTATION_DEFINITIONS.get(code);
  }

  @Override
  public List<ProblemDefinition> definitions() {
    return List.of(
        new ProblemDefinition(
            URI.create("https://api.timing-jeju.com/problems/schedule-version-not-found"),
            "일정 버전을 찾을 수 없습니다",
            404,
            "SCHEDULE_VERSION_NOT_FOUND",
            "요청한 일정 버전이 없거나 해당 여행에 속하지 않습니다."),
        problem("ACCOMMODATION_NOT_FOUND", "숙소를 찾을 수 없습니다", 404, "요청한 숙소가 없거나 해당 여행에 속하지 않습니다."),
        problem(
            "ACTIVE_SCHEDULE_VERSION_CONFLICT",
            "활성 일정이 이미 변경되었습니다",
            409,
            "최신 활성 일정을 조회한 뒤 다시 편집해 주세요."),
        problem(
            "SCHEDULE_ITEM_INVALID", "일정 항목을 적용할 수 없습니다", 422, "항목 유형별 필수값과 Day 시간 범위를 확인해 주세요."),
        problem(
            "SCHEDULE_LEG_INCOMPLETE", "이동 구간을 완성할 수 없습니다", 422, "인접 일정 항목 사이의 이동 구간을 확인해 주세요."));
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

  private static Map.Entry<String, ProblemDefinition> mutation(
      String code, String title, int status, String detail) {
    return Map.entry(code, problem(code, title, status, detail));
  }
}
