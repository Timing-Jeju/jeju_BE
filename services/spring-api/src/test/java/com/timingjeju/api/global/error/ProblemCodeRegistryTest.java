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
    assertThat(registry.find("UPSTREAM_ERROR").status()).isEqualTo(502);
    assertThat(registry.find("NOT_REGISTERED").code()).isEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(registry.find("NOT_REGISTERED").status()).isEqualTo(500);
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
