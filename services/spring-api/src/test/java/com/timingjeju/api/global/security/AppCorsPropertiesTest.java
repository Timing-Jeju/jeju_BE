package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "http://0x7f000001",
        "http://0X7F000001",
        "http://0x7f000001.",
        "http://0X7F000001.",
        "http://0x",
        "http://0X",
        "http://0x.",
        "http://0X.",
        "http://127.0.0.1.",
        "http://127.0x.0.1",
        "http://0x.0.0.1",
        "http://127.0x.0.1.",
        "http://0x7f.0.0.1",
        "http://127.0x0.0.1",
        "http://0x7f.0x0.0x0.0x1",
        "HTTP://[0:0:0:0:0:0:0:1]:80",
        "http://0177.0.0.1:80",
        "http://127.1",
        "http://2130706433",
        "http://127.00.0.1",
        "http://001.2.3.4",
        "http://1.2.3.04",
        "http://256.0.0.1",
        "http://[2001:0db8::1]",
        "http://[0:0::1]"
      })
  void 브라우저와_다르게_직렬화되는_비정규_numeric_host는_시작_설정이_실패한다(String nonCanonicalNumericOrigin) {
    assertThatThrownBy(() -> new AppCorsProperties(List.of(nonCanonicalNumericOrigin)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Origin");
  }

  @Test
  void DNS_trailing_root_dot은_브라우저_origin처럼_보존한다() {
    AppCorsProperties properties =
        new AppCorsProperties(
            List.of(
                "HTTP://EXAMPLE.COM.:80",
                "http://localhost.",
                "http://0x.example.com.",
                "http://0xg.example.com.",
                "http://api-0x7f.example.com."));

    assertThat(properties.allowedOrigins())
        .containsExactly(
            "http://example.com.",
            "http://localhost.",
            "http://0x.example.com.",
            "http://0xg.example.com.",
            "http://api-0x7f.example.com.");
  }

  @Test
  void canonical_IP와_DNS_like_hostname은_허용한다() {
    AppCorsProperties properties =
        new AppCorsProperties(
            List.of(
                "http://127.0.0.1:80",
                "http://0.0.0.0",
                "http://255.255.255.255",
                "http://[::1]:80",
                "http://[::]",
                "https://[2001:db8::1]:443",
                "HTTPS://[2001:DB8::1]",
                "https://[2001:db8:0:1::1]",
                "https://[2001:db8:0:1:2:3:4:5]",
                "https://123.example.com",
                "https://1.2.3.example",
                "https://0x.example.com",
                "https://api-0x7f.example.com",
                "https://deadbeef.example",
                "https://x7f000001.example",
                "https://0xg.example.com",
                "http://localhost"));

    assertThat(properties.allowedOrigins())
        .containsExactly(
            "http://127.0.0.1",
            "http://0.0.0.0",
            "http://255.255.255.255",
            "http://[::1]",
            "http://[::]",
            "https://[2001:db8::1]",
            "https://[2001:db8:0:1::1]",
            "https://[2001:db8:0:1:2:3:4:5]",
            "https://123.example.com",
            "https://1.2.3.example",
            "https://0x.example.com",
            "https://api-0x7f.example.com",
            "https://deadbeef.example",
            "https://x7f000001.example",
            "https://0xg.example.com",
            "http://localhost");
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
