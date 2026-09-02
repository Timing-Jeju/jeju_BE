package com.timingjeju.api.global.datahealth;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExternalDataHealthOperatorProperties.class)
public class ExternalDataHealthEndpointConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "app.data-health.operator",
      name = "enabled",
      havingValue = "true")
  ExternalDataHealthEndpoint externalDataHealthEndpoint(
      CompletedProviderDataHealthService service, Environment environment) {
    validateSeparateManagementPort(environment);
    return new ExternalDataHealthEndpoint(service);
  }

  static void validateSeparateManagementPort(Environment environment) {
    String managementPort = environment.getProperty("management.server.port");
    String applicationPort = environment.getProperty("server.port", "8080");
    if (managementPort == null
        || managementPort.isBlank()
        || (!"0".equals(managementPort) && managementPort.equals(applicationPort))) {
      throw new IllegalStateException("운영 상세 진단은 별도 management.server.port가 필요합니다.");
    }
  }
}
