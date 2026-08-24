package com.timingjeju.api.global.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.retention.SnapshotRetentionCycleResult;
import com.timingjeju.api.application.retention.SnapshotRetentionOrchestrator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@Tag("unit")
@ExtendWith(OutputCaptureExtension.class)
class SnapshotRetentionSchedulerConfigurationTest {
  private final SnapshotRetentionOrchestrator orchestrator =
      mock(SnapshotRetentionOrchestrator.class);
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(SnapshotRetentionSchedulerConfiguration.class)
          .withBean(SnapshotRetentionOrchestrator.class, () -> orchestrator)
          .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  void schedule은_기본값과_false에서_bean이_없고_true에서_정확히_하나만_생성된다() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(SnapshotRetentionScheduler.class);
        });
    contextRunner
        .withPropertyValues("app.snapshot-retention.schedule.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(SnapshotRetentionScheduler.class));
    contextRunner
        .withPropertyValues("app.snapshot-retention.schedule.enabled=true")
        .run(context -> assertThat(context).getBeans(SnapshotRetentionScheduler.class).hasSize(1));
  }

  @Test
  void startup_one_shot과_schedule을_동시에_true로_두면_context가_fail_fast한다() {
    contextRunner
        .withPropertyValues(
            "app.snapshot-retention.enabled=true", "app.snapshot-retention.schedule.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasMessageContaining("snapshot retention")
                  .hasMessageContaining("동시에");
            });
  }

  @Test
  void schedule_boolean과_delay_batch_retry_backoff_범위를_typed_binding에서_거부한다() {
    assertBindingFailure("app.snapshot-retention.schedule.enabled=truthy");
    assertBindingFailure(
        "app.snapshot-retention.schedule.enabled=true",
        "app.snapshot-retention.schedule.fixed-delay=PT59S");
    assertBindingFailure(
        "app.snapshot-retention.schedule.enabled=true",
        "app.snapshot-retention.schedule.initial-delay=PT24H0.001S");
    assertBindingFailure(
        "app.snapshot-retention.schedule.enabled=true",
        "app.snapshot-retention.schedule.max-batches=11");
    assertBindingFailure(
        "app.snapshot-retention.schedule.enabled=true",
        "app.snapshot-retention.schedule.retry-attempts=4");
    assertBindingFailure(
        "app.snapshot-retention.schedule.enabled=true",
        "app.snapshot-retention.schedule.initial-backoff=PT1.001S");
    assertBindingFailure(
        "app.snapshot-retention.schedule.retry-attempts=3",
        "app.snapshot-retention.schedule.initial-backoff=PT0.5S");
  }

  @Test
  void 실행중인_tick과_겹친_second_tick은_orchestrator를_호출하지_않는다() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(orchestrator.execute(any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await(2, TimeUnit.SECONDS);
              return mock(SnapshotRetentionCycleResult.class);
            });

    contextRunner
        .withPropertyValues("app.snapshot-retention.schedule.enabled=true")
        .run(
            context -> {
              SnapshotRetentionScheduler scheduler =
                  context.getBean(SnapshotRetentionScheduler.class);
              try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(scheduler::tick);
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(scheduler::tick);
                second.get(1, TimeUnit.SECONDS);
                release.countDown();
                first.get(1, TimeUnit.SECONDS);
              }
              verify(orchestrator, times(1)).execute(any());
            });
  }

  @Test
  void scheduled_경계는_programmer_exception을_고정문구로_격리하고_lock해제후_다음_tick을_실행한다(CapturedOutput output) {
    IllegalStateException raw =
        new IllegalStateException(
            "credential=should-not-leak cause=driver SELECT * FROM external_api_snapshots");
    when(orchestrator.execute(any()))
        .thenThrow(raw)
        .thenReturn(mock(SnapshotRetentionCycleResult.class));

    contextRunner
        .withPropertyValues("app.snapshot-retention.schedule.enabled=true")
        .run(
            context -> {
              SnapshotRetentionScheduler scheduler =
                  context.getBean(SnapshotRetentionScheduler.class);

              scheduler.tick();
              scheduler.tick();

              verify(orchestrator, times(2)).execute(any());
              assertThat(output).contains("snapshot_retention scheduled cycle failed");
              assertThat(output)
                  .doesNotContain(
                      raw.getMessage(), "credential", "driver", "SELECT", "external_api_snapshots");
            });
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
}
