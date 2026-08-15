package com.timingjeju.api.global.tourapi.detail;

import com.timingjeju.api.application.tourapi.detail.DetailCommonParser;
import com.timingjeju.api.application.tourapi.detail.DetailCommonSource;
import com.timingjeju.api.application.tourapi.detail.DetailIntroParser;
import com.timingjeju.api.application.tourapi.detail.DetailIntroSource;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailImportService;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PlaceDetailImportConfiguration {
  @Bean
  PlaceDetailImportService placeDetailImportService(
      DetailCommonSource commonSource,
      DetailIntroSource introSource,
      DetailCommonParser commonParser,
      DetailIntroParser introParser,
      PlaceDetailRepository repository,
      Clock clock) {
    return new PlaceDetailImportService(
        commonSource, introSource, commonParser, introParser, repository, clock);
  }
}
