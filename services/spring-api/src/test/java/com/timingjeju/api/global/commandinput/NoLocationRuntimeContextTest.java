package com.timingjeju.api.global.commandinput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class NoLocationRuntimeContextTest {
  @Test
  void 과거_설정을_켜도_위치_스케줄러와_지표_bean은_생성되지_않는다() {
    new ApplicationContextRunner()
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withPropertyValues("app.command-location-cleanup.schedule.enabled=true")
        .withUserConfiguration(InputConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(JdbcCommandInputSnapshotRepository.class);
              assertThat(context.getBeanDefinitionNames())
                  .noneMatch(
                      name -> name.toLowerCase(java.util.Locale.ROOT).contains("locationcleanup"));
              for (String type :
                  new String[] {
                    "application.commandinput.CoarseLocation",
                    "application.commandinput.CommandLocation",
                    "application.commandinput.CommandLocationSnapshot",
                    "application.commandinput.McpCommandLocationResolver",
                    "global.commandinput.cleanup.CommandLocationCleanupConfiguration",
                    "global.commandinput.cleanup.CommandLocationCleanupScheduler",
                    "global.commandinput.cleanup.CommandLocationCleanupMetrics"
                  }) {
                assertThatThrownBy(
                        () -> context.getClassLoader().loadClass("com.timingjeju.api." + type))
                    .isInstanceOf(ClassNotFoundException.class);
              }
            });
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackages = {
        "com.timingjeju.api.application.commandinput",
        "com.timingjeju.api.global.commandinput"
      })
  static class InputConfiguration {}
}
