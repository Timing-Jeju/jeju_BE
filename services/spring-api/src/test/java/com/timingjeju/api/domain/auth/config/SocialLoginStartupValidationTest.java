package com.timingjeju.api.domain.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@Tag("unit")
class SocialLoginStartupValidationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(SocialLoginTestConfiguration.class);

  @Test
  void 소셜_로그인_설정은_정확한_공급자와_redirect_allowlist에서만_시작한다() {
    contextRunner
        .withPropertyValues(
            "app.social-login.enabled-provider-ids=google,kakao,custom:naver",
            "app.social-login.redirect-urls=https://app.timing-jeju.test/auth/callback,http://127.0.0.1:3000/auth/callback")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void 중복_미허용_공급자와_open_redirect_형식은_context를_실패시킨다() {
    contextRunner
        .withPropertyValues(
            "app.social-login.enabled-provider-ids=google,google",
            "app.social-login.redirect-urls=https://app.timing-jeju.test/auth/callback")
        .run(context -> assertThat(context).hasFailed());
    contextRunner
        .withPropertyValues(
            "app.social-login.enabled-provider-ids=github",
            "app.social-login.redirect-urls=https://app.timing-jeju.test/auth/callback")
        .run(context -> assertThat(context).hasFailed());
    contextRunner
        .withPropertyValues(
            "app.social-login.enabled-provider-ids=google",
            "app.social-login.redirect-urls=https://app.timing-jeju.test/auth/callback?next=https://evil.test")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SocialLoginProperties.class)
  static class SocialLoginTestConfiguration {}
}
