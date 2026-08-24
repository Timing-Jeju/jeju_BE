package com.timingjeju.api.domain.legal.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class LegalProfileProblemDefinitions implements ProblemDefinitionContributor {

  @Override
  public List<ProblemDefinition> definitions() {
    return List.of(
        ProblemDefinition.forCode(
            "PROFILE_CONFLICT", "프로필 연결 충돌", 409, "인증 프로필을 현재 사용자에게 안전하게 연결할 수 없습니다."),
        ProblemDefinition.forCode(
            "LEGAL_CONSENT_REQUIRED", "필수 동의 필요", 422, "현재 시행 중인 필수 법정 문서에 모두 동의해야 합니다."));
  }
}
