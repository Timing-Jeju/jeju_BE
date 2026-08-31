package com.timingjeju.api.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProblemCodeRegistryTest {

  @Test
  void 표준_registry는_validation_not_found_conflict_upstream_unknown_500을_분류한다() {
    ProblemCodeRegistry registry = new ProblemCodeRegistry(List.of());

    assertThat(registry.find("VALIDATION_FAILED").status()).isEqualTo(400);
    assertThat(registry.find("RESOURCE_NOT_FOUND").status()).isEqualTo(404);
    assertThat(registry.find("CONFLICT").status()).isEqualTo(409);
    assertThat(registry.find("IDEMPOTENCY_KEY_REQUIRED").status()).isEqualTo(400);
    assertThat(registry.find("IDEMPOTENCY_KEY_INVALID").status()).isEqualTo(400);
    assertThat(registry.find("IDEMPOTENCY_KEY_REUSED").status()).isEqualTo(409);
    assertThat(registry.find("UPSTREAM_ERROR").status()).isEqualTo(502);
    assertThat(registry.find("NOT_REGISTERED").code()).isEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(registry.find("NOT_REGISTERED").status()).isEqualTo(500);
  }

  @Test
  void 공통_인증_problem은_canonical_code와_URI_문구로_각각_한번만_등록된다() {
    ProblemCodeRegistry registry = new ProblemCodeRegistry(List.of());

    assertThat(registry.find("AUTHENTICATION_REQUIRED"))
        .isEqualTo(
            new ProblemDefinition(
                URI.create("https://api.timing-jeju.com/problems/authentication-required"),
                "인증이 필요합니다",
                401,
                "AUTHENTICATION_REQUIRED",
                "로그인 후 다시 요청해 주세요."));
    assertThat(registry.find("INVALID_ACCESS_TOKEN"))
        .isEqualTo(
            new ProblemDefinition(
                URI.create("https://api.timing-jeju.com/problems/invalid-access-token"),
                "인증 정보가 올바르지 않습니다",
                401,
                "INVALID_ACCESS_TOKEN",
                "유효한 인증 정보로 다시 요청해 주세요."));
    assertThat(registry.find("AUTH_TOKEN_INVALID"))
        .isEqualTo(
            ProblemDefinition.forCode(
                "AUTH_TOKEN_INVALID", "인증에 실패했습니다.", 401, "인증 토큰이 유효하지 않습니다."));
  }

  @Test
  void contributor를_추가하면_기존_registry를_수정하지_않고_도메인_code를_확장한다() {
    ProblemDefinition custom =
        new ProblemDefinition(
            URI.create("https://api.timing-jeju.example/problems/place-not-found"),
            "장소를 찾을 수 없습니다.",
            404,
            "PLACE_NOT_FOUND",
            "요청한 장소가 존재하지 않습니다.");
    ProblemCodeRegistry registry = new ProblemCodeRegistry(List.of(() -> List.of(custom)));

    assertThat(registry.find("PLACE_NOT_FOUND")).isEqualTo(custom);
  }

  @Test
  void 중복_code는_시작할_때_거부한다() {
    ProblemDefinition duplicate =
        new ProblemDefinition(
            URI.create("https://api.timing-jeju.example/problems/duplicate"),
            "중복",
            400,
            "VALIDATION_FAILED",
            "중복 code");

    assertThatThrownBy(() -> new ProblemCodeRegistry(List.of(() -> List.of(duplicate))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("VALIDATION_FAILED");
  }
}
