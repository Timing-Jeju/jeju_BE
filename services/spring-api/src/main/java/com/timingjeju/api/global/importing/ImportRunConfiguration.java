package com.timingjeju.api.global.importing;

import com.timingjeju.api.application.importing.ImportRunIdentityGenerator;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ImportRunConfiguration {

  @Bean
  ImportRunIdentityGenerator importRunIdentityGenerator() {
    return new UuidImportRunIdentityGenerator();
  }

  @Bean
  ImportRunLifecycleService importRunLifecycleService(
      ImportRunStore store, Clock clock, ImportRunIdentityGenerator identityGenerator) {
    return new ImportRunLifecycleService(store, clock, identityGenerator);
  }
}
