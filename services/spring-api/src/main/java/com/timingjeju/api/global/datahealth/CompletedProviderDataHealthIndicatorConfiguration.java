package com.timingjeju.api.global.datahealth;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CompletedProviderDataHealthActuatorProperties.class)
public class CompletedProviderDataHealthIndicatorConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "app.data-health.actuator",
      name = "enabled",
      havingValue = "true")
  CompletedProviderDataHealthIndicator completedProviderDataHealthIndicator(
      CompletedProviderDataHealthService service) {
    return new CompletedProviderDataHealthIndicator(service);
  }
}
