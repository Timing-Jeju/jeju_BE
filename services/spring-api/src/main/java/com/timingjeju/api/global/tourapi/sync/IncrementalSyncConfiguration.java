package com.timingjeju.api.global.tourapi.sync;

import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCommitter;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncParser;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncService;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncSnapshotGateway;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncSource;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IncrementalSyncConfiguration {
  @Bean
  IncrementalSyncService incrementalSyncService(
      IncrementalSyncSource source,
      IncrementalSyncSnapshotGateway snapshots,
      IncrementalSyncParser parser,
      ImportCheckpointService checkpoints,
      ImportRunLifecycleService runs,
      IncrementalSyncCommitter committer,
      Clock clock) {
    return new IncrementalSyncService(
        source, snapshots, parser, checkpoints, runs, committer, clock);
  }
}
