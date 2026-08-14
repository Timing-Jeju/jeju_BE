package com.timingjeju.api.global.snapshot;

import com.timingjeju.api.application.snapshot.SnapshotIdentityGenerator;
import com.timingjeju.api.application.snapshot.SnapshotRedactor;
import com.timingjeju.api.application.snapshot.SnapshotStore;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class SnapshotStoreConfiguration {

  @Bean
  SnapshotRedactor snapshotRedactor(ObjectMapper objectMapper) {
    return new DeterministicSnapshotRedactor(objectMapper);
  }

  @Bean
  SnapshotIdentityGenerator snapshotIdentityGenerator() {
    return UUID::randomUUID;
  }

  @Bean
  SnapshotStoreService snapshotStoreService(
      SnapshotStore store,
      SnapshotRedactor redactor,
      Clock clock,
      SnapshotIdentityGenerator identityGenerator) {
    return new SnapshotStoreService(store, redactor, clock, identityGenerator);
  }
}
