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
  void 소셜_로그인_설정은_지원_공급자_목록으로_시작한다() {
    contextRunner
        .withPropertyValues("app.social-login.provider-ids=google,kakao,custom:naver")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void 중복_미허용_공급자는_context를_실패시킨다() {
    contextRunner
        .withPropertyValues("app.social-login.provider-ids=google,google")
        .run(context -> assertThat(context).hasFailed());
    contextRunner
        .withPropertyValues("app.social-login.provider-ids=github")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SocialLoginProperties.class)
  static class SocialLoginTestConfiguration {}
}
