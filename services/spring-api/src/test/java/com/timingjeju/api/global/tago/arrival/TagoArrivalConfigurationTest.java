package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheService;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitter;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightCoordinator;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStore;
import com.timingjeju.api.application.tago.arrival.TagoArrivalImportSession;
import com.timingjeju.api.application.tago.arrival.TagoArrivalLoadService;
import com.timingjeju.api.application.tago.arrival.TagoArrivalPayloadParser;
import com.timingjeju.api.application.tago.arrival.TagoArrivalProcessor;
import com.timingjeju.api.application.tago.arrival.TagoArrivalRepository;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshotGateway;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSource;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class TagoArrivalConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(TagoArrivalConfiguration.class, Dependencies.class);

  @Test
  void fenced_processor_coordinator_loader_cache가_중복없이_구성된다() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(TagoArrivalProcessor.class);
          assertThat(context).hasSingleBean(TagoArrivalFlightCoordinator.class);
          assertThat(context).hasSingleBean(TagoArrivalLoadService.class);
          assertThat(context).hasSingleBean(TagoArrivalCacheService.class);
        });
  }

  @Configuration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    TagoArrivalSource source() {
      return mock(TagoArrivalSource.class);
    }

    @Bean
    TagoArrivalPayloadParser parser() {
      return mock(TagoArrivalPayloadParser.class);
    }

    @Bean
    TagoArrivalImportSession session() {
      return mock(TagoArrivalImportSession.class);
    }

    @Bean
    TagoArrivalSnapshotGateway snapshots() {
      return mock(TagoArrivalSnapshotGateway.class);
    }

    @Bean
    TagoArrivalCommitter committer() {
      return mock(TagoArrivalCommitter.class);
    }

    @Bean
    TagoArrivalFlightStore flights() {
      return mock(TagoArrivalFlightStore.class);
    }

    @Bean
    TagoArrivalRepository repository() {
      return mock(TagoArrivalRepository.class);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
