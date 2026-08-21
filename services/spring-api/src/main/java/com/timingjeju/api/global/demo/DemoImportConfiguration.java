package com.timingjeju.api.global.demo;

import com.timingjeju.api.application.demo.DemoImportService;
import com.timingjeju.api.application.demo.DemoStorageReader;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.tourapi.detail.DetailCommonParser;
import com.timingjeju.api.application.tourapi.detail.DetailCommonSource;
import com.timingjeju.api.application.tourapi.detail.DetailIntroParser;
import com.timingjeju.api.application.tourapi.detail.DetailIntroSource;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailRepository;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportService;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportService;
import com.timingjeju.api.application.tourapi.place.PlaceListImportService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class DemoImportConfiguration {
  @Bean
  com.timingjeju.api.application.demo.DemoImportService demoImportService(
      PlaceListImportService importer,
      DemoStorageReader reader,
      ImportRunLifecycleService runService,
      SnapshotStoreService snapshotService,
      DetailCommonSource commonSource,
      DetailCommonParser commonParser,
      DetailIntroSource introSource,
      DetailIntroParser introParser,
      PlaceDetailRepository detailRepository,
      DetailItemImportService detailItemImportService,
      PlaceImageImportService detailImageImportService,
      ObjectMapper objectMapper,
      Clock clock) {
    return new DemoImportService(
        importer,
        reader,
        runService,
        snapshotService,
        commonSource,
        commonParser,
        introSource,
        introParser,
        detailRepository,
        detailItemImportService,
        detailImageImportService,
        clock,
        objectMapper);
  }

  @Bean
  com.timingjeju.api.domain.demo.service.DemoImportService demoImportFacadeService(
      com.timingjeju.api.application.demo.DemoImportService applicationDemoImportService) {
    return new com.timingjeju.api.domain.demo.service.DemoImportService(
        applicationDemoImportService);
  }
}
