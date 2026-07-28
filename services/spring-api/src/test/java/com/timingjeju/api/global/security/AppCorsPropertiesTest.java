package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AppCorsPropertiesTest {

  @Test
  void null과_빈값과_공백_쉼표뿐인_allowlist는_시작_설정이_실패한다() {
    assertThatThrownBy(() -> new AppCorsProperties(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CORS 허용 Origin");
    assertThatThrownBy(() -> new AppCorsProperties(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CORS 허용 Origin");
    assertThatThrownBy(() -> new AppCorsProperties(List.of(" ", ",", " , ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CORS 허용 Origin");
  }

  @Test
  void origin은_trim과_중복제거를_적용하고_wildcard는_거부한다() {
    AppCorsProperties properties =
        new AppCorsProperties(
            List.of(
                " http://localhost:3000 ",
                "https://app.timing-jeju.test",
                "http://localhost:3000"));

    assertThat(properties.allowedOrigins())
        .containsExactly("http://localhost:3000", "https://app.timing-jeju.test");
    assertThatThrownBy(() -> new AppCorsProperties(List.of("*")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wildcard");
  }
}
