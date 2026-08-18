package com.timingjeju.api.global.tago.route;

import com.timingjeju.api.application.tago.route.TagoRouteImportCommitter;
import com.timingjeju.api.application.tago.route.TagoRouteImportService;
import com.timingjeju.api.application.tago.route.TagoRouteImportSession;
import com.timingjeju.api.application.tago.route.TagoRoutePayloadParser;
import com.timingjeju.api.application.tago.route.TagoRouteSnapshotGateway;
import com.timingjeju.api.application.tago.route.TagoRouteSource;
import com.timingjeju.api.application.tago.route.TagoRouteStopCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TagoRouteImportConfiguration {
  @Bean
  TagoRouteImportService tagoRouteImportService(
      TagoRouteSource source,
      TagoRoutePayloadParser parser,
      TagoRouteImportSession session,
      TagoRouteSnapshotGateway snapshots,
      TagoRouteStopCatalog stops,
      TagoRouteImportCommitter committer) {
    return new TagoRouteImportService(source, parser, session, snapshots, stops, committer);
  }
}
