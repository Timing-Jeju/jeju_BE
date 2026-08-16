package com.timingjeju.api.global.tago.stop;

import com.timingjeju.api.application.tago.stop.TagoStopImportCommitter;
import com.timingjeju.api.application.tago.stop.TagoStopImportService;
import com.timingjeju.api.application.tago.stop.TagoStopImportSession;
import com.timingjeju.api.application.tago.stop.TagoStopPayloadParser;
import com.timingjeju.api.application.tago.stop.TagoStopSnapshotGateway;
import com.timingjeju.api.application.tago.stop.TagoStopSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TagoStopImportConfiguration {
  @Bean
  TagoStopImportService tagoStopImportService(
      TagoStopSource source,
      TagoStopPayloadParser parser,
      TagoStopImportSession session,
      TagoStopSnapshotGateway snapshots,
      TagoStopImportCommitter committer) {
    return new TagoStopImportService(source, parser, session, snapshots, committer);
  }
}
