package com.timingjeju.api.global.commandinput.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import com.timingjeju.api.application.commandinput.McpCommandLocationResolver;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupCycleResult;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupOrchestrator;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupPort;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
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
class CommandLocationCleanupConfigurationTest {
  private final CommandLocationCleanupPort port = mock(CommandLocationCleanupPort.class);
  private final CommandInputSnapshotRepository snapshots =
      mock(CommandInputSnapshotRepository.class);
  private final CommandLocationCleanupOrchestrator orchestrator =
      mock(CommandLocationCleanupOrchestrator.class);
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(CommandLocationCleanupConfiguration.class)
          .withBean(CommandLocationCleanupPort.class, () -> port)
          .withBean(CommandInputSnapshotRepository.class, () -> snapshots)
          .withBean(CommandLocationCleanupOrchestrator.class, () -> orchestrator)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  void application_service와_MCP직전_resolver는_내부bean이고_scheduler만_기본비활성이다() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(CommandLocationCleanupService.class);
          assertThat(context).hasSingleBean(McpCommandLocationResolver.class);
          assertThat(context).doesNotHaveBean(CommandLocationCleanupScheduler.class);
        });
    contextRunner
        .withPropertyValues("app.command-location-cleanup.schedule.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(CommandLocationCleanupScheduler.class));
  }

  @Test
  void production_safe_default는_5분_500건_10batch_1분_deadline이다() {
    contextRunner.run(
        context -> {
          CommandLocationCleanupProperties properties =
              context.getBean(CommandLocationCleanupProperties.class);
          assertThat(properties.schedule().enabled()).isFalse();
          assertThat(properties.schedule().fixedDelay()).isEqualTo(java.time.Duration.ofMinutes(5));
          assertThat(properties.batchSize()).isEqualTo(500);
          assertThat(properties.schedule().maxBatches()).isEqualTo(10);
          assertThat(properties.schedule().maxDuration())
              .isEqualTo(java.time.Duration.ofMinutes(1));
        });
  }

  @Test
  void boolean_batch_maxBatch_retry_delay_deadline_invalid값은_typed_binding에서_거부한다() {
    assertBindingFailure("app.command-location-cleanup.schedule.enabled=truthy");
    assertBindingFailure("app.command-location-cleanup.batch-size=501");
    assertBindingFailure("app.command-location-cleanup.schedule.max-batches=11");
    assertBindingFailure("app.command-location-cleanup.schedule.retry-attempts=4");
    assertBindingFailure("app.command-location-cleanup.schedule.fixed-delay=PT4M59S");
    assertBindingFailure("app.command-location-cleanup.schedule.max-duration=PT1M0.001S");
  }

  @Test
  void 겹친_tick은_process내_중복호출을_막는다() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(orchestrator.execute(any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await(2, TimeUnit.SECONDS);
              return mock(CommandLocationCleanupCycleResult.class);
            });

    contextRunner
        .withPropertyValues("app.command-location-cleanup.schedule.enabled=true")
        .run(
            context -> {
              var scheduler = context.getBean(CommandLocationCleanupScheduler.class);
              try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(scheduler::tick);
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(scheduler::tick);
                second.get(1, TimeUnit.SECONDS);
                release.countDown();
                first.get(1, TimeUnit.SECONDS);
              }
              verify(orchestrator).execute(any());
            });
  }

  @Test
  void scheduler_failure_log는_raw위치_exception_SQL을_노출하지_않고_다음tick을_허용한다(CapturedOutput output) {
    RuntimeException raw =
        new IllegalStateException("gridX=333 gridY=777 coarse_location SELECT secret");
    when(orchestrator.execute(any()))
        .thenThrow(raw)
        .thenReturn(mock(CommandLocationCleanupCycleResult.class));

    contextRunner
        .withPropertyValues("app.command-location-cleanup.schedule.enabled=true")
        .run(
            context -> {
              var scheduler = context.getBean(CommandLocationCleanupScheduler.class);
              scheduler.tick();
              scheduler.tick();
              verify(orchestrator, times(2)).execute(any());
              assertThat(output).contains("command_location_cleanup scheduled cycle failed");
              assertThat(output)
                  .doesNotContain("gridX", "gridY", "coarse_location", "SELECT", "secret");
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
