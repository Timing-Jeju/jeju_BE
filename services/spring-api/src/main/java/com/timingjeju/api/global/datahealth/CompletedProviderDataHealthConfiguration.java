package com.timingjeju.api.global.datahealth;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthCatalog;
import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import com.timingjeju.api.application.datahealth.ProviderDataHealthReader;
import com.timingjeju.api.global.externalapi.KmaProperties;
import com.timingjeju.api.global.externalapi.TagoProperties;
import com.timingjeju.api.global.externalapi.TourApiProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CompletedProviderDataHealthConfiguration {

  @Bean
  CompletedProviderDataHealthService completedProviderDataHealthService(
      ProviderDataHealthReader reader,
      Clock clock,
      TourApiProperties tourApi,
      TagoProperties tago,
      KmaProperties kma) {
    return new CompletedProviderDataHealthService(
        reader,
        clock,
        CompletedProviderDataHealthCatalog.settings(
            tourApi.enabled(), tago.enabled(), kma.enabled()));
  }
}
