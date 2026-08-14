package com.timingjeju.api.global.importing;

import com.timingjeju.api.application.importing.ImportCheckpointRepository;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ImportCheckpointConfiguration {

  @Bean
  ImportCheckpointService importCheckpointService(ImportCheckpointRepository repository) {
    return new ImportCheckpointService(repository);
  }
}
