package com.timingjeju.api.global.tourapi.reference;

import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeParser;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeRepository;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSource;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSyncService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReferenceCodeSyncConfiguration {

  @Bean
  ReferenceCodeSyncService referenceCodeSyncService(
      ReferenceCodeSource source,
      ReferenceCodeParser parser,
      ReferenceCodeRepository repository,
      ImportRunLifecycleService runService,
      SnapshotStoreService snapshotService,
      Clock clock) {
    return new ReferenceCodeSyncService(
        source, parser, repository, runService, snapshotService, clock);
  }
}
