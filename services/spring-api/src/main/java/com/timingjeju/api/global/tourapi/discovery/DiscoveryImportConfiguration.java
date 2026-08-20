package com.timingjeju.api.global.tourapi.discovery;

import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryCommitter;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryImportService;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryParser;
import com.timingjeju.api.application.tourapi.discovery.DiscoverySchedulePolicy;
import com.timingjeju.api.application.tourapi.discovery.DiscoverySource;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DiscoveryImportConfiguration {

  @Bean
  DiscoveryImportService discoveryImportService(
      DiscoverySource source,
      DiscoveryParser parser,
      DiscoveryCommitter committer,
      ImportRunLifecycleService runService,
      ImportCheckpointService checkpointService,
      SnapshotStoreService snapshotService,
      Clock clock) {
    return new DiscoveryImportService(
        source, parser, committer, runService, checkpointService, snapshotService, clock);
  }

  @Bean
  DiscoverySchedulePolicy discoverySchedulePolicy() {
    return DiscoverySchedulePolicy.safeDefault();
  }
}
