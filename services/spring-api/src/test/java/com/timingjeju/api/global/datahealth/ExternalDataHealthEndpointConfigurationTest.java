package com.timingjeju.api.global.datahealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ExternalDataHealthEndpointConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(ExternalDataHealthEndpointConfiguration.class, Dependencies.class);

  @Test
  void 기본_비활성은_상세_endpoint를_만들지_않는다() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ExternalDataHealthEndpoint.class);
        });
  }

  @Test
  void 별도_management_port가_있는_경우에만_상세_endpoint를_만든다() {
    contextRunner
        .withPropertyValues("app.data-health.operator.enabled=true", "management.server.port=9091")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ExternalDataHealthEndpoint.class);
            });
  }

  @Test
  void management_port가_없거나_application_port와_같으면_fail_fast다() {
    contextRunner
        .withPropertyValues("app.data-health.operator.enabled=true")
        .run(context -> assertThat(context).hasFailed());
    contextRunner
        .withPropertyValues(
            "app.data-health.operator.enabled=true",
            "server.port=9091",
            "management.server.port=9091")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    CompletedProviderDataHealthService service() {
      return mock(CompletedProviderDataHealthService.class);
    }
  }
}
