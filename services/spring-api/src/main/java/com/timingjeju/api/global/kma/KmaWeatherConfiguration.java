package com.timingjeju.api.global.kma;

import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.kma.KmaWeatherBaseTimeResolver;
import com.timingjeju.api.application.kma.KmaWeatherCommitter;
import com.timingjeju.api.application.kma.KmaWeatherImportService;
import com.timingjeju.api.application.kma.KmaWeatherParser;
import com.timingjeju.api.application.kma.KmaWeatherSnapshotGateway;
import com.timingjeju.api.application.kma.KmaWeatherSource;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class KmaWeatherConfiguration {

  @Bean
  KmaWeatherBaseTimeResolver kmaWeatherBaseTimeResolver(Clock clock) {
    return new KmaWeatherBaseTimeResolver(clock);
  }

  @Bean
  KmaWeatherImportService kmaWeatherImportService(
      KmaWeatherSource source,
      KmaWeatherSnapshotGateway snapshots,
      KmaWeatherParser parser,
      ImportCheckpointService checkpoints,
      ImportRunLifecycleService runs,
      KmaWeatherCommitter committer,
      KmaWeatherBaseTimeResolver bases) {
    return new KmaWeatherImportService(
        source, snapshots, parser, checkpoints, runs, committer, bases);
  }
}
