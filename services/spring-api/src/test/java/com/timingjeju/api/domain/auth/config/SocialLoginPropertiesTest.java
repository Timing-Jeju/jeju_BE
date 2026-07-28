package com.timingjeju.api.domain.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SocialLoginPropertiesTest {

  @Test
  void 공개할_지원_공급자_목록을_생성한다() {
    SocialLoginProperties properties =
        new SocialLoginProperties(List.of("google", "kakao", "custom:naver"));

    assertThat(properties.providerIds()).containsExactly("google", "kakao", "custom:naver");
  }

  @Test
  void 중복_알수없는_공급자와_빈_목록은_fail_fast한다() {
    assertThatThrownBy(() -> new SocialLoginProperties(List.of("google", "google")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SocialLoginProperties(List.of("github")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SocialLoginProperties(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
