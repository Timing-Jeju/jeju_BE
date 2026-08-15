package com.timingjeju.api.global.tourapi.detailitem;

import com.timingjeju.api.application.tourapi.detailitem.DetailInfoParser;
import com.timingjeju.api.application.tourapi.detailitem.DetailInfoSource;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportService;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DetailItemImportConfiguration {
  @Bean
  DetailItemImportService detailItemImportService(
      DetailInfoSource source,
      DetailInfoParser parser,
      DetailItemRepository repository,
      Clock clock) {
    return new DetailItemImportService(source, parser, repository, clock);
  }
}
