package com.timingjeju.api.global.retention;

import com.timingjeju.api.application.retention.SavedPlaceRetentionTask;
import com.timingjeju.api.global.security.SecurityRuntimeEnvironment;
import com.timingjeju.api.global.security.SecurityRuntimeEnvironmentResolver;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(SavedPlaceRetentionProperties.class)
public class SavedPlaceRetentionSchedulerConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "app.saved-place-retention",
      name = "enabled",
      havingValue = "true")
  SavedPlaceRetentionScheduler savedPlaceRetentionScheduler(
      SavedPlaceRetentionTask task, SavedPlaceRetentionProperties properties) {
    return new SavedPlaceRetentionScheduler(task, properties.maxBatches());
  }

  @Bean
  InitializingBean requireSavedPlaceRetentionInProduction(
      SavedPlaceRetentionProperties properties, Environment environment) {
    return () -> {
      if (SecurityRuntimeEnvironmentResolver.resolve(environment).environment()
              == SecurityRuntimeEnvironment.PRODUCTION
          && !properties.enabled()) {
        throw new IllegalStateException("saved-place retention must be enabled in production");
      }
    };
  }
}
