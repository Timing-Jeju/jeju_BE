package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheService;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitter;
import com.timingjeju.api.application.tago.arrival.TagoArrivalDistributedFlightCoordinator;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightCoordinator;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightPolicy;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStore;
import com.timingjeju.api.application.tago.arrival.TagoArrivalImportSession;
import com.timingjeju.api.application.tago.arrival.TagoArrivalLoadService;
import com.timingjeju.api.application.tago.arrival.TagoArrivalPayloadParser;
import com.timingjeju.api.application.tago.arrival.TagoArrivalRepository;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshotGateway;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSource;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TagoArrivalConfiguration {
  static final Duration FRESH_TTL = Duration.ofSeconds(25);
  static final Duration STALE_WINDOW = Duration.ofMinutes(2);
  static final TagoArrivalFlightPolicy FLIGHT_POLICY =
      new TagoArrivalFlightPolicy(
          Duration.ofSeconds(10),
          Duration.ofMillis(25),
          Duration.ofSeconds(8),
          Duration.ofSeconds(12),
          Duration.ofSeconds(25),
          Duration.ofSeconds(12));

  @Bean
  TagoArrivalFlightCoordinator tagoArrivalFlightCoordinator(TagoArrivalFlightStore store) {
    return new TagoArrivalDistributedFlightCoordinator(store, FLIGHT_POLICY);
  }

  @Bean
  TagoArrivalLoadService tagoArrivalLoadService(
      TagoArrivalSource source,
      TagoArrivalPayloadParser parser,
      TagoArrivalImportSession session,
      TagoArrivalSnapshotGateway snapshots,
      TagoArrivalCommitter committer,
      Clock clock) {
    return new TagoArrivalLoadService(
        source, parser, session, snapshots, committer, clock, FRESH_TTL);
  }

  @Bean
  TagoArrivalCacheService tagoArrivalCacheService(
      TagoArrivalLoadService loader,
      TagoArrivalRepository repository,
      TagoArrivalFlightCoordinator coordinator,
      Clock clock) {
    return new TagoArrivalCacheService(
        loader, repository, coordinator, clock, FRESH_TTL, STALE_WINDOW);
  }
}
