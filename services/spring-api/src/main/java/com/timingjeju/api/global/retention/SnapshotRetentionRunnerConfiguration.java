package com.timingjeju.api.global.retention;

import com.timingjeju.api.application.retention.SnapshotRetentionPort;
import com.timingjeju.api.application.retention.SnapshotRetentionResult;
import com.timingjeju.api.application.retention.SnapshotRetentionService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SnapshotRetentionProperties.class)
public class SnapshotRetentionRunnerConfiguration {
  private static final Logger log =
      LoggerFactory.getLogger(SnapshotRetentionRunnerConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(SnapshotRetentionService.class)
  SnapshotRetentionService snapshotRetentionService(SnapshotRetentionPort port, Clock clock) {
    return new SnapshotRetentionService(port, clock);
  }

  @Bean
  @ConditionalOnProperty(prefix = "app.snapshot-retention", name = "enabled", havingValue = "true")
  ApplicationRunner snapshotRetentionRunner(
      SnapshotRetentionService service, SnapshotRetentionProperties properties) {
    return arguments -> {
      SnapshotRetentionResult result = service.execute(properties.dryRun(), properties.batchSize());
      log.info(
          "snapshot_retention outcome={} dryRun={} candidateCount={} purgedCount={} durationMs={}",
          result.outcome(),
          result.dryRun(),
          result.candidateCount(),
          result.purgedCount(),
          result.duration().toMillis());
    };
  }
}
