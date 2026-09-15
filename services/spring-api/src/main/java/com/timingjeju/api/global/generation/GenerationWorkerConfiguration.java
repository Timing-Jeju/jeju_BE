package com.timingjeju.api.global.generation;

import com.timingjeju.api.application.asyncrun.RunExecutionPolicy;
import com.timingjeju.api.application.asyncrun.ThreadedRunExecutionSupervisor;
import com.timingjeju.api.application.generation.*;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.schedule-generation", name = "enabled", havingValue = "true")
@EnableScheduling
public class GenerationWorkerConfiguration {
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnProperty(
      prefix = "app.schedule-generation.worker",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  GenerationWorker generationWorker(
      GenerationRunLeases leases,
      GenerationPlanExecutor planner,
      GenerationCompletionStore completions,
      Clock clock) {
    var policy =
        new RunExecutionPolicy(
            Duration.ofSeconds(180),
            Duration.ofSeconds(10),
            1,
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(60),
            Duration.ofSeconds(675));
    return new GenerationWorker(
        "generation:" + UUID.randomUUID(),
        leases,
        planner,
        completions,
        ThreadedRunExecutionSupervisor.create(clock, 1),
        policy,
        clock,
        () -> ThreadLocalRandom.current().nextDouble());
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "app.schedule-generation.worker",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  GenerationPolling generationPolling(GenerationWorker worker) {
    return new GenerationPolling(worker);
  }

  static final class GenerationPolling {
    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(GenerationPolling.class);
    private final GenerationWorker worker;

    GenerationPolling(GenerationWorker worker) {
      this.worker = worker;
    }

    @Scheduled(
        fixedDelayString = "${app.schedule-generation.worker.fixed-delay:2000}",
        initialDelayString = "${app.schedule-generation.worker.initial-delay:2000}")
    public void poll() {
      try {
        worker.pollOnce();
      } catch (RuntimeException failure) {
        // JDBC/provider 예외의 message·cause·SQL을 scheduler 기본 로거로 넘기지 않는다.
        LOG.warn("GENERATION_POLL_FAILED");
      }
    }
  }
}
