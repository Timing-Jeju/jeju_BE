package com.timingjeju.api.global.tourapi.place;

import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.tourapi.place.PlaceListImportService;
import com.timingjeju.api.application.tourapi.place.PlaceListParser;
import com.timingjeju.api.application.tourapi.place.PlaceListRepository;
import com.timingjeju.api.application.tourapi.place.PlaceListSource;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PlaceListImportConfiguration {

  @Bean
  PlaceListImportService placeListImportService(
      PlaceListSource source,
      PlaceListParser parser,
      PlaceListRepository repository,
      ImportRunLifecycleService runService,
      SnapshotStoreService snapshotService,
      Clock clock) {
    return new PlaceListImportService(
        source, parser, repository, runService, snapshotService, clock);
  }
}
