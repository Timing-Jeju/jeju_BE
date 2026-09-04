package com.timingjeju.api.global.commandinput.cleanup;

import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import com.timingjeju.api.application.commandinput.McpCommandLocationResolver;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupCycleCommand;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupOrchestrator;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupPort;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CommandLocationCleanupProperties.class)
public class CommandLocationCleanupConfiguration {

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "app.command-location-cleanup.schedule",
      name = "enabled",
      havingValue = "true")
  static class SchedulingEnabledConfiguration {}

  @Bean
  @ConditionalOnMissingBean(CommandLocationCleanupService.class)
  CommandLocationCleanupService commandLocationCleanupService(
      CommandLocationCleanupPort port, Clock clock) {
    return new CommandLocationCleanupService(port, clock);
  }

  @Bean
  @ConditionalOnMissingBean(McpCommandLocationResolver.class)
  McpCommandLocationResolver mcpCommandLocationResolver(
      CommandInputSnapshotRepository repository, Clock clock) {
    return new McpCommandLocationResolver(repository, clock);
  }

  @Bean
  @ConditionalOnMissingBean(CommandLocationCleanupMetrics.class)
  CommandLocationCleanupMetrics commandLocationCleanupMetrics(MeterRegistry registry) {
    return new CommandLocationCleanupMetrics(registry);
  }

  @Bean
  @ConditionalOnMissingBean(CommandLocationCleanupOrchestrator.class)
  CommandLocationCleanupOrchestrator commandLocationCleanupOrchestrator(
      CommandLocationCleanupService service, CommandLocationCleanupMetrics metrics) {
    return new CommandLocationCleanupOrchestrator(
        service, duration -> Thread.sleep(duration.toMillis()), metrics, System::nanoTime);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "app.command-location-cleanup.schedule",
      name = "enabled",
      havingValue = "true")
  CommandLocationCleanupScheduler commandLocationCleanupScheduler(
      CommandLocationCleanupOrchestrator orchestrator,
      CommandLocationCleanupProperties properties) {
    CommandLocationCleanupProperties.Schedule schedule = properties.schedule();
    return new CommandLocationCleanupScheduler(
        orchestrator,
        new CommandLocationCleanupCycleCommand(
            properties.batchSize(),
            schedule.maxBatches(),
            schedule.retryAttempts(),
            schedule.initialBackoff(),
            schedule.maxDuration()));
  }
}
