package com.timingjeju.api.domain.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SocialLoginPropertiesTest {

  @Test
  void 허용한_공급자와_정확한_redirect_목록만_생성한다() {
    SocialLoginProperties properties =
        new SocialLoginProperties(
            List.of("google", "kakao", "custom:naver"),
            List.of(
                "https://app.timing-jeju.test/auth/callback",
                "http://127.0.0.1:3000/auth/callback"));

    assertThat(properties.enabledProviderIds()).containsExactly("google", "kakao", "custom:naver");
    assertThat(properties.redirectUrls())
        .containsExactly(
            "https://app.timing-jeju.test/auth/callback", "http://127.0.0.1:3000/auth/callback");
  }

  @Test
  void 중복_알수없는_공급자와_빈_목록은_fail_fast한다() {
    assertThatThrownBy(
            () ->
                new SocialLoginProperties(
                    List.of("google", "google"),
                    List.of("https://app.timing-jeju.test/auth/callback")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SocialLoginProperties(
                    List.of("github"), List.of("https://app.timing-jeju.test/auth/callback")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SocialLoginProperties(
                    List.of(), List.of("https://app.timing-jeju.test/auth/callback")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void wildcard_상대_URL_임의_도메인_HTTP와_query_fragment가_있는_redirect는_fail_fast한다() {
    List<String> invalidRedirects =
        List.of(
            "https://*.timing-jeju.test/auth/callback",
            "/auth/callback",
            "http://app.timing-jeju.test/auth/callback",
            "https://app.timing-jeju.test/auth/callback?next=https://evil.test",
            "https://app.timing-jeju.test/auth/callback#fragment",
            "https://app.timing-jeju.test/../auth/callback");

    for (String invalidRedirect : invalidRedirects) {
      assertThatThrownBy(
              () -> new SocialLoginProperties(List.of("google"), List.of(invalidRedirect)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
