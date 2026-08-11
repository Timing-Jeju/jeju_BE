package com.timingjeju.api.global.externalapi;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  TourApiProperties.class,
  TagoProperties.class,
  TmapProperties.class,
  KmaProperties.class
})
public class ExternalApiConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "app.external-api.tour-api",
      name = "enabled",
      havingValue = "true")
  ExternalApiClientSettings tourApiClientSettings(
      TourApiProperties properties, Environment environment) {
    return clientSettings(properties, environment);
  }

  @Bean
  @ConditionalOnProperty(prefix = "app.external-api.tago", name = "enabled", havingValue = "true")
  ExternalApiClientSettings tagoClientSettings(TagoProperties properties, Environment environment) {
    return clientSettings(properties, environment);
  }

  @Bean
  @ConditionalOnProperty(prefix = "app.external-api.tmap", name = "enabled", havingValue = "true")
  ExternalApiClientSettings tmapClientSettings(TmapProperties properties, Environment environment) {
    return clientSettings(properties, environment);
  }

  @Bean
  @ConditionalOnProperty(prefix = "app.external-api.kma", name = "enabled", havingValue = "true")
  ExternalApiClientSettings kmaClientSettings(KmaProperties properties, Environment environment) {
    return clientSettings(properties, environment);
  }

  @Bean
  InfoContributor externalApiInfoContributor(
      TourApiProperties tourApi, TagoProperties tago, TmapProperties tmap, KmaProperties kma) {
    Map<String, Boolean> statuses = new LinkedHashMap<>();
    statuses.put(tourApi.provider().actuatorName(), tourApi.enabled());
    statuses.put(tago.provider().actuatorName(), tago.enabled());
    statuses.put(tmap.provider().actuatorName(), tmap.enabled());
    statuses.put(kma.provider().actuatorName(), kma.enabled());
    Map<String, Boolean> immutableStatuses = Map.copyOf(statuses);
    return builder -> builder.withDetail("externalApis", immutableStatuses);
  }

  private static ExternalApiClientSettings clientSettings(
      ExternalApiProviderProperties properties, Environment environment) {
    ExternalApiRuntimeEnvironment runtime =
        ExternalApiRuntimeEnvironmentResolver.resolve(environment);
    if (runtime == ExternalApiRuntimeEnvironment.PRODUCTION
        && !"https".equalsIgnoreCase(properties.baseUrl().getScheme())) {
      throw new IllegalStateException(
          properties.provider().environmentName("BASE_URL") + "는 기본/운영 환경에서 HTTPS URL이어야 합니다.");
    }
    return ExternalApiClientSettings.from(properties);
  }
}
