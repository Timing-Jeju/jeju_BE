package com.timingjeju.api.global.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.retention.SavedPlaceRetentionTask;
import com.timingjeju.api.application.retention.SnapshotRetentionOutcome;
import com.timingjeju.api.application.retention.SnapshotRetentionResult;
import com.timingjeju.api.application.retention.SnapshotRetentionService;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class SnapshotRetentionRunnerConfigurationTest {
  private final SavedPlaceRetentionTask ancillaryRetention = mock(SavedPlaceRetentionTask.class);
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(SnapshotRetentionRunnerConfiguration.class)
          .withBean(SnapshotRetentionService.class, () -> mock(SnapshotRetentionService.class))
          .withBean(SavedPlaceRetentionTask.class, () -> ancillaryRetention);

  @Test
  void 기본값과_false는_runner가_없고_service를_호출하지_않는다() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ApplicationRunner.class);
          verify(context.getBean(SnapshotRetentionService.class), never()).execute(true, 500);
        });
    contextRunner
        .withPropertyValues("app.snapshot-retention.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
  }

  @Test
  void enabled의_기본_dryRun_true와_batch500을_startup에서_정확히_한번_호출한다() {
    contextRunner
        .withPropertyValues("app.snapshot-retention.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              SnapshotRetentionService service = context.getBean(SnapshotRetentionService.class);
              when(service.execute(true, 500)).thenReturn(success(true));

              context.getBean(ApplicationRunner.class).run(arguments());

              verify(service).execute(true, 500);
              verify(ancillaryRetention, never()).drain(10);
              assertThat(context).getBeans(ApplicationRunner.class).hasSize(1);
            });
  }

  @Test
  void 명시한_mutation과_batch를_한번만_실행하고_retry_loop를_두지_않는다() {
    contextRunner
        .withPropertyValues(
            "app.snapshot-retention.enabled=true",
            "app.snapshot-retention.dry-run=false",
            "app.snapshot-retention.batch-size=1")
        .run(
            context -> {
              SnapshotRetentionService service = context.getBean(SnapshotRetentionService.class);
              when(service.execute(false, 1)).thenReturn(success(false));

              context.getBean(ApplicationRunner.class).run(arguments());

              verify(service).execute(false, 1);
            });
  }

  @Test
  void 잘못된_boolean과_batch_범위_밖은_binding에서_fail_fast한다() {
    assertBindingFailure("app.snapshot-retention.enabled=truthy");
    assertBindingFailure("app.snapshot-retention.dry-run=truthy");
    assertBindingFailure(
        "app.snapshot-retention.enabled=true", "app.snapshot-retention.batch-size=0");
    assertBindingFailure(
        "app.snapshot-retention.enabled=true", "app.snapshot-retention.batch-size=501");
  }

  private void assertBindingFailure(String... properties) {
    contextRunner
        .withPropertyValues(properties)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(causeChain(context.getStartupFailure()))
                  .anyMatch(ConfigurationPropertiesBindException.class::isInstance)
                  .anyMatch(BindException.class::isInstance);
            });
  }

  private static java.util.List<Throwable> causeChain(Throwable failure) {
    java.util.ArrayList<Throwable> causes = new java.util.ArrayList<>();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      causes.add(current);
    }
    return java.util.List.copyOf(causes);
  }

  private static SnapshotRetentionResult success(boolean dryRun) {
    return new SnapshotRetentionResult(
        1, dryRun ? 0 : 1, Duration.ofMillis(2), SnapshotRetentionOutcome.SUCCESS, dryRun);
  }

  private static ApplicationArguments arguments() {
    return new DefaultApplicationArguments(new String[0]);
  }
}
