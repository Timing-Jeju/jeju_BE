package com.timingjeju.api.global.tourapi.image;

import com.timingjeju.api.application.tourapi.image.DetailImageParser;
import com.timingjeju.api.application.tourapi.image.DetailImageSnapshotGateway;
import com.timingjeju.api.application.tourapi.image.DetailImageSource;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportService;
import com.timingjeju.api.application.tourapi.image.PlaceImageRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PlaceImageImportConfiguration {
  @Bean
  PlaceImageImportService placeImageImportService(
      DetailImageSource source,
      DetailImageSnapshotGateway snapshots,
      DetailImageParser parser,
      PlaceImageRepository repository,
      Clock clock) {
    return new PlaceImageImportService(source, snapshots, parser, repository, clock);
  }
}
