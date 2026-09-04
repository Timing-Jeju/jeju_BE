package com.timingjeju.api.domain.schedule.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class ScheduleProblemDefinitions implements ProblemDefinitionContributor {
  @Override
  public List<ProblemDefinition> definitions() {
    return List.of(
        new ProblemDefinition(
            URI.create("https://api.timing-jeju.com/problems/schedule-version-not-found"),
            "일정 버전을 찾을 수 없습니다",
            404,
            "SCHEDULE_VERSION_NOT_FOUND",
            "요청한 일정 버전이 없거나 접근할 수 없습니다."),
        problem("ACCOMMODATION_NOT_FOUND", "숙소를 찾을 수 없습니다", 404, "요청한 숙소가 없거나 해당 여행에 속하지 않습니다."),
        problem(
            "TRANSPORT_EVENT_NOT_FOUND",
            "교통 이벤트를 찾을 수 없습니다",
            404,
            "요청한 교통 이벤트가 없거나 해당 여행에 속하지 않습니다."),
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
}
