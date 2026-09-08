package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.service.ProfileImageCleanupService;
import com.timingjeju.api.application.profile.service.ProfileImageOrphanService;
import com.timingjeju.api.global.security.SecurityRuntimeEnvironment;
import com.timingjeju.api.global.security.SecurityRuntimeEnvironmentResolver;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileImageMaintenanceProperties.class)
class ProfileImageMaintenanceConfiguration {

  @Bean
  InitializingBean requireProfileImageMaintenanceInProduction(
      ProfileImageMaintenanceProperties properties,
      ObjectProvider<ProfileImageStorageSettings> storageProvider,
      Environment environment) {
    return () -> {
      ProfileImageStorageSettings storage = storageProvider.getIfAvailable();
      if (storage != null
          && storage.enabled()
          && SecurityRuntimeEnvironmentResolver.resolve(environment).environment()
              == SecurityRuntimeEnvironment.PRODUCTION
          && !properties.enabled()) {
        throw new IllegalStateException("profile image maintenance must be enabled in production");
      }
    };
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @Conditional(StorageEnabledCondition.class)
  @ConditionalOnProperty(
      prefix = "app.profile-image.maintenance",
      name = "enabled",
      havingValue = "true")
  static class SchedulingConfiguration {

    @Bean
    ProfileImageMaintenanceRunner profileImageMaintenanceRunner(
        ProfileImageCleanupService cleanup, ProfileImageOrphanService orphan) {
      return new ProfileImageMaintenanceRunner(cleanup, orphan);
    }
  }

  static final class StorageEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      if (context.getBeanFactory() == null) {
        return false;
      }
      String[] names =
          context
              .getBeanFactory()
              .getBeanNamesForType(ProfileImageStorageSettings.class, true, false);
      if (names.length == 0) {
        return hasText(context.getEnvironment().getProperty("app.profile-image.supabase-url"))
            && hasText(context.getEnvironment().getProperty("app.profile-image.service-role-key"));
      }
      if (names.length != 1) {
        return false;
      }
      ProfileImageStorageSettings settings =
          context.getBeanFactory().getBean(names[0], ProfileImageStorageSettings.class);
      return settings.enabled();
    }

    private static boolean hasText(String value) {
      return value != null && !value.isBlank();
    }
  }
}
