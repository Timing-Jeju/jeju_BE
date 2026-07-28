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
                " HTTP://LOCALHOST:3000 ",
                "https://APP.timing-jeju.test",
                "http://localhost:3000"));

    assertThat(properties.allowedOrigins())
        .containsExactly("http://localhost:3000", "https://app.timing-jeju.test");
    assertThatThrownBy(() -> new AppCorsProperties(List.of("*")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wildcard");
  }

  @Test
  void localhost_도메인_IPv4_IPv6와_유효한_port_origin은_허용한다() {
    AppCorsProperties properties =
        new AppCorsProperties(
            List.of(
                "http://localhost",
                "https://example.com:443",
                "http://127.0.0.1:8080",
                "http://[::1]:3000"));

    assertThat(properties.allowedOrigins())
        .containsExactly(
            "http://localhost",
            "https://example.com",
            "http://127.0.0.1:8080",
            "http://[::1]:3000");
  }

  @Test
  void 기본_port는_생략형으로_정규화하고_중복제거하며_비기본_port는_보존한다() {
    AppCorsProperties properties =
        new AppCorsProperties(
            List.of(
                "http://example.com:80",
                "http://example.com",
                "https://example.com:443",
                "https://example.com",
                "http://localhost:80",
                "https://127.0.0.1:443",
                "http://[::1]:80",
                "https://[2001:db8::1]:443",
                "http://example.com:8080",
                "https://example.com:8443"));

    assertThat(properties.allowedOrigins())
        .containsExactly(
            "http://example.com",
            "https://example.com",
            "http://localhost",
            "https://127.0.0.1",
            "http://[::1]",
            "https://[2001:db8::1]",
            "http://example.com:8080",
            "https://example.com:8443");
  }

  @Test
  void origin이_아닌_URI와_parser_우회는_시작_설정이_실패한다() {
    for (String invalidOrigin :
        List.of(
            "ftp://example.com",
            "https://user@example.com",
            "https://user%40example.com@example.com",
            "https://example.com/",
            "https://example.com/path",
            "https://example.com/%2F",
            "https://example.com?query=1",
            "https://example.com#fragment",
            "https://*.example.com",
            "https://example.*",
            "http://*",
            "relative/path",
            "//example.com",
            "http:///path",
            "http://:8080",
            "http://example.com:0",
            "http://example.com:65536",
            "http://example.com:-1",
            "http://example.com:abc",
            "http://[::1")) {
      assertThatThrownBy(() -> new AppCorsProperties(List.of(invalidOrigin)))
          .as(invalidOrigin)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Origin");
    }
  }
}
