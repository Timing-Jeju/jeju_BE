package com.timingjeju.api.global.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.timingjeju.api.application.retention.SavedPlaceRetentionTask;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class SavedPlaceRetentionSchedulerConfigurationTest {
  private final SavedPlaceRetentionTask retention = mock(SavedPlaceRetentionTask.class);
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(SavedPlaceRetentionSchedulerConfiguration.class)
          .withBean(SavedPlaceRetentionTask.class, () -> retention);

  @Test
  void 명시적_mutating_mode는_HTTP_traffic과_snapshot_flags없이_retention을_실행한다() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=local", "app.saved-place-retention.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              SavedPlaceRetentionScheduler scheduler =
                  context.getBean(SavedPlaceRetentionScheduler.class);

              scheduler.tick();

              verify(retention).drain(10);
            });
  }

  @Test
  void 명시적_local_profiles만_disabled를_허용하고_mutation하지_않는다() {
    for (String profile : java.util.List.of("local", "local-hs256")) {
      contextRunner
          .withPropertyValues("spring.profiles.active=" + profile)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(SavedPlaceRetentionScheduler.class);
                verify(retention, never()).drain(10);
              });
    }
  }

  @Test
  void no_profile과_staging_test를_포함한_production분류는_disabled로_기동하지_않는다() {
    contextRunner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasMessageContaining("saved-place retention");
        });
    for (String profile : java.util.List.of("staging", "test", "prod", "production")) {
      contextRunner
          .withPropertyValues("spring.profiles.active=" + profile)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("saved-place retention")
                    .hasMessageContaining("production");
              });
    }
  }

  @Test
  void no_profile과_staging_production분류는_enabled이면_scheduler를_생성한다() {
    contextRunner
        .withPropertyValues("app.saved-place-retention.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(SavedPlaceRetentionScheduler.class);
            });
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=staging", "app.saved-place-retention.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(SavedPlaceRetentionScheduler.class);
            });
  }
}
