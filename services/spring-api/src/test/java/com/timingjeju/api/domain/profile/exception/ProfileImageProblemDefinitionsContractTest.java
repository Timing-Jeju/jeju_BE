package com.timingjeju.api.domain.profile.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.global.error.ProblemDefinition;
import java.net.URI;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfileImageProblemDefinitionsContractTest {

  @Test
  void contract_1_1_1의_canonical_problem_identity를_exact하게_제공한다() {
    Map<String, ProblemDefinition> definitions =
        new ProfileImageProblemDefinitions()
            .definitions().stream()
                .collect(Collectors.toMap(ProblemDefinition::code, Function.identity()));

    assertProblem(
        definitions, "IDEMPOTENCY_PAYLOAD_CONFLICT", "멱등 요청 충돌", 409, "같은 멱등성 키의 요청 본문이 다릅니다.");
    assertProblem(
        definitions, "IDEMPOTENCY_REQUEST_IN_PROGRESS", "멱등 요청 처리 중", 409, "동일한 요청이 처리 중입니다.");
    assertProblem(
        definitions, "PROFILE_IMAGE_NOT_FOUND", "프로필 이미지 없음", 404, "확정할 프로필 이미지를 찾을 수 없습니다.");
    assertProblem(
        definitions, "PROFILE_IMAGE_TOO_LARGE", "프로필 이미지 용량 초과", 413, "프로필 이미지는 5 MiB 이하여야 합니다.");
    assertProblem(
        definitions,
        "PROFILE_IMAGE_STORAGE_UNAVAILABLE",
        "프로필 이미지 저장소 오류",
        503,
        "프로필 이미지 저장소를 확인할 수 없습니다.");
  }

  private static void assertProblem(
      Map<String, ProblemDefinition> definitions,
      String code,
      String title,
      int status,
      String detail) {
    assertThat(definitions.get(code))
        .isEqualTo(
            new ProblemDefinition(
                URI.create(
                    "https://api.timing-jeju.com/problems/"
                        + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')),
                title,
                status,
                code,
                detail));
  }
}
