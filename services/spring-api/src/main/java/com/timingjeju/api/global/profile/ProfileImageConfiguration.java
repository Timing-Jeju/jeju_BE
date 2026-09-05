package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.profile.ProfileImageCleanupStore;
import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMutationExecutor;
import com.timingjeju.api.application.profile.ProfileImagePublicUrl;
import com.timingjeju.api.application.profile.ProfileImageStorageCatalog;
import com.timingjeju.api.application.profile.ProfileImageStorageMetadataReader;
import com.timingjeju.api.application.profile.ProfileImageStorageObjectDeleter;
import com.timingjeju.api.application.profile.ProfileImageStore;
import com.timingjeju.api.application.profile.service.ProfileImageCleanupService;
import com.timingjeju.api.application.profile.service.ProfileImageOrphanService;
import com.timingjeju.api.application.profile.service.ProfileImageService;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileImageProperties.class)
public class ProfileImageConfiguration {

  private static final Set<String> LOCAL_PROFILES = Set.of("local", "local-hs256");

  @Bean
  ProfileImageStorageSettings profileImageStorageSettings(
      ProfileImageProperties properties, Environment environment) {
    boolean local =
        environment.getActiveProfiles().length == 1
            && LOCAL_PROFILES.contains(environment.getActiveProfiles()[0]);
    return ProfileImageStorageSettings.from(properties, local);
  }

  @Bean
  ProfileImageStorageHttpTransport profileImageStorageHttpTransport(
      ProfileImageStorageSettings settings) {
    return new JdkProfileImageStorageHttpTransport(settings);
  }

  @Bean
  ProfileImagePublicUrl profileImagePublicUrl(ProfileImageStorageSettings settings) {
    return objectKey -> {
      if (settings.baseUrl() == null) {
        throw ProfileImageException.storageUnavailable();
      }
      String base = settings.baseUrl().toString().replaceAll("/+$", "");
      return base + "/storage/v1/object/public/profile-images/" + objectKey;
    };
  }

  @Bean
  SupabaseProfileImageMetadataReader profileImageStorageClient(
      ProfileImageStorageSettings settings,
      tools.jackson.databind.ObjectMapper objectMapper,
      ProfileImageStorageHttpTransport transport) {
    return new SupabaseProfileImageMetadataReader(settings, objectMapper, transport);
  }

  @Bean
  ProfileImageMutationExecutor profileImageMutationExecutor(
      com.timingjeju.api.application.idempotency.IdempotencyUseCase idempotency,
      tools.jackson.databind.ObjectMapper objectMapper) {
    return new IdempotentProfileImageMutationExecutor(idempotency, objectMapper);
  }

  @Bean
  ProfileImageService profileImageService(
      CurrentUserProvisioningService provisioning,
      ProfileImageStore profiles,
      ProfileImageStorageMetadataReader metadataReader,
      ProfileImagePublicUrl publicUrl,
      ProfileImageMutationExecutor mutations,
      Clock clock) {
    return new ProfileImageService(
        provisioning, profiles, metadataReader, publicUrl, mutations, clock);
  }

  @Bean
  ProfileImageCleanupService profileImageCleanupService(
      ProfileImageCleanupStore store,
      ProfileImageStorageMetadataReader metadataReader,
      ProfileImageStorageObjectDeleter deleter,
      Clock clock) {
    return new ProfileImageCleanupService(
        store,
        metadataReader,
        deleter,
        clock,
        Duration.ofMinutes(2),
        16,
        Duration.ofMinutes(1),
        Duration.ofHours(1));
  }

  @Bean
  ProfileImageOrphanService profileImageOrphanService(
      ProfileImageStorageMetadataReader metadataReader,
      ProfileImageStorageCatalog catalog,
      ProfileImageCleanupStore store,
      Clock clock) {
    return new ProfileImageOrphanService(
        metadataReader, catalog, store, clock, Duration.ofHours(24), 100, 10);
  }
}
