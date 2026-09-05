package com.timingjeju.api.domain.profile.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class ProfileImageProblemDefinitions implements ProblemDefinitionContributor {

  @Override
  public List<ProblemDefinition> definitions() {
    return List.of(
        problem("INVALID_PROFILE_IMAGE_REQUEST", "프로필 이미지 요청 오류", 400, "프로필 이미지 요청 형식이 올바르지 않습니다."),
        problem("PROFILE_IMAGE_NOT_FOUND", "프로필 이미지 없음", 404, "확정할 프로필 이미지를 찾을 수 없습니다."),
        problem("PROFILE_IMAGE_VERSION_CONFLICT", "프로필 이미지 버전 충돌", 409, "프로필 이미지 상태가 변경되었습니다."),
        problem("IDEMPOTENCY_PAYLOAD_CONFLICT", "멱등 요청 충돌", 409, "같은 멱등성 키의 요청 본문이 다릅니다."),
        problem("IDEMPOTENCY_REQUEST_IN_PROGRESS", "멱등 요청 처리 중", 409, "동일한 요청이 처리 중입니다."),
        problem("PROFILE_IMAGE_TOO_LARGE", "프로필 이미지 용량 초과", 413, "프로필 이미지는 5 MiB 이하여야 합니다."),
        problem(
            "PROFILE_IMAGE_MEDIA_TYPE_UNSUPPORTED", "프로필 이미지 형식 오류", 415, "지원하지 않는 프로필 이미지 형식입니다."),
        problem(
            "PROFILE_IMAGE_STORAGE_UNAVAILABLE",
            "프로필 이미지 저장소 오류",
            503,
            "프로필 이미지 저장소를 확인할 수 없습니다."));
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
