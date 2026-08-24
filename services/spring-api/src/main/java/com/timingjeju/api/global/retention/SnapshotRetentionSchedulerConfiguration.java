package com.timingjeju.api.global.retention;

import com.timingjeju.api.application.retention.SnapshotRetentionCycleCommand;
import com.timingjeju.api.application.retention.SnapshotRetentionOrchestrator;
import com.timingjeju.api.application.retention.SnapshotRetentionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  SnapshotRetentionProperties.class,
  SnapshotRetentionScheduleProperties.class
})
public class SnapshotRetentionSchedulerConfiguration {

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "app.snapshot-retention.schedule",
      name = "enabled",
      havingValue = "true")
  static class SchedulingEnabledConfiguration {}

  @Bean
  @ConditionalOnMissingBean(SnapshotRetentionMetrics.class)
  SnapshotRetentionMetrics snapshotRetentionMetrics(MeterRegistry registry) {
    return new SnapshotRetentionMetrics(registry);
  }

  @Bean
  @ConditionalOnMissingBean(SnapshotRetentionOrchestrator.class)
  SnapshotRetentionOrchestrator snapshotRetentionOrchestrator(
      SnapshotRetentionService service, SnapshotRetentionMetrics metrics) {
    return new SnapshotRetentionOrchestrator(
        service, duration -> Thread.sleep(duration.toMillis()), metrics, System::nanoTime);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "app.snapshot-retention.schedule",
      name = "enabled",
      havingValue = "true")
  SnapshotRetentionScheduler snapshotRetentionScheduler(
      SnapshotRetentionOrchestrator orchestrator,
      SnapshotRetentionProperties retention,
      SnapshotRetentionScheduleProperties schedule) {
    if (retention.enabled()) {
      throw new IllegalStateException("snapshot retention one-shot과 schedule을 동시에 활성화할 수 없습니다.");
    }
    return new SnapshotRetentionScheduler(
        orchestrator,
        new SnapshotRetentionCycleCommand(
            retention.dryRun(),
            retention.batchSize(),
            schedule.maxBatches(),
            schedule.retryAttempts(),
            schedule.initialBackoff()));
  }
}
