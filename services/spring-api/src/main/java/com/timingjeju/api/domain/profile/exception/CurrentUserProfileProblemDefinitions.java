package com.timingjeju.api.domain.profile.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class CurrentUserProfileProblemDefinitions implements ProblemDefinitionContributor {

  @Override
  public List<ProblemDefinition> definitions() {
    return List.of(
        ProblemDefinition.forCode(
            "INVALID_PROFILE_LEGAL_REQUEST", "요청 형식 오류", 400, "프로필 수정 요청 형식이 올바르지 않습니다."),
        ProblemDefinition.forCode(
            "PROFILE_DATA_UNAVAILABLE", "프로필 조회 불가", 503, "프로필 데이터를 불러올 수 없습니다."));
  }
}
