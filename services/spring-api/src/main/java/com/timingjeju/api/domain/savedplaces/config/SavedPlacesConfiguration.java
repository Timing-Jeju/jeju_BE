package com.timingjeju.api.domain.savedplaces.config;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;

@Configuration(proxyBeanMethods = false)
public class SavedPlacesConfiguration {
  @Bean
  JsonFactoryBuilderCustomizer strictJsonDuplicateDetection() {
    return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }
}
