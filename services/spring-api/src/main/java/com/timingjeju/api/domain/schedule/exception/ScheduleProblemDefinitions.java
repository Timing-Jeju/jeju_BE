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
            "요청한 일정 버전이 없거나 접근할 수 없습니다."));
  }
}
