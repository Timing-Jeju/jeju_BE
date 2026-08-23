package com.timingjeju.api.global.datahealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CompletedProviderDataHealthIndicatorConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              CompletedProviderDataHealthIndicatorConfiguration.class, Dependencies.class);

  @Test
  void 설정이_누락되거나_false면_indicator_bean이_없다() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(CompletedProviderDataHealthIndicator.class);
        });
    contextRunner
        .withPropertyValues("app.data-health.actuator.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(CompletedProviderDataHealthIndicator.class);
            });
  }

  @Test
  void true면_indicator_bean이_정확히_하나_생긴다() {
    contextRunner
        .withPropertyValues("app.data-health.actuator.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(CompletedProviderDataHealthIndicator.class);
            });
  }

  @Test
  void boolean이_아닌_명시값은_binding_fail_fast다() {
    contextRunner
        .withPropertyValues("app.data-health.actuator.enabled=enabled")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .isInstanceOf(ConfigurationPropertiesBindException.class)
                  .hasCauseInstanceOf(BindException.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    CompletedProviderDataHealthService service() {
      return mock(CompletedProviderDataHealthService.class);
    }
  }
}
