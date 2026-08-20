package com.timingjeju.api.domain.places.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CanonicalPlaceCategoryTest {

  @Test
  void source_lclsSystm1은_trim_uppercase하고_없으면_numeric_content_type으로_fallback한다() {
    assertThat(CanonicalPlaceCategory.fromSource(" ve ", "12")).isEqualTo("VE");
    assertThat(CanonicalPlaceCategory.fromSource(null, " 99 ")).isEqualTo("content-type:99");
  }

  @Test
  void 임의문자열_공백_제어문자_credential_like_category를_거부한다() {
    assertThat(CanonicalPlaceCategory.isValid("VE")).isTrue();
    assertThat(CanonicalPlaceCategory.isValid("content-type:99")).isTrue();
    assertThat(CanonicalPlaceCategory.isValid("tourist_attraction")).isFalse();
    assertThat(CanonicalPlaceCategory.isValid(" VE ")).isFalse();
    assertThat(CanonicalPlaceCategory.isValid("VE\n")).isFalse();
    assertThat(CanonicalPlaceCategory.isValid("API_KEY")).isFalse();
    assertThatThrownBy(() -> CanonicalPlaceCategory.fromSource("api_key", "12"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
