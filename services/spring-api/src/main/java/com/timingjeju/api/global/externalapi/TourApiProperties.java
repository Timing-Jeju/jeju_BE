package com.timingjeju.api.global.externalapi;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.external-api.tour-api")
public record TourApiProperties(
    @DefaultValue("false") boolean enabled,
    String apiKey,
    URI baseUrl,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout)
    implements ExternalApiProviderProperties {

  public TourApiProperties {
    ExternalApiPropertyValidation.validate(
        new ExternalApiPropertyValues(
            ExternalApiProvider.TOUR_API, enabled, apiKey, baseUrl, connectTimeout, readTimeout));
  }

  @Override
  public ExternalApiProvider provider() {
    return ExternalApiProvider.TOUR_API;
  }

  @Override
  public String toString() {
    return ExternalApiPropertyValidation.safeDescription(this);
  }
}
