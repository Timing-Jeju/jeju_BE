package com.timingjeju.api.global.timetable;

import com.timingjeju.api.application.timetable.JejuTimetableImportService;
import com.timingjeju.api.application.timetable.TimetableCatalog;
import com.timingjeju.api.application.timetable.TimetableImportCommand;
import com.timingjeju.api.application.timetable.TimetableImportStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(name = "timing-jeju.timetable-import.enabled", havingValue = "true")
class JejuTimetableImportRunnerConfiguration {
  private static final Logger log =
      LoggerFactory.getLogger(JejuTimetableImportRunnerConfiguration.class);

  @Bean
  ApplicationRunner jejuTimetableImportRunner(
      Environment environment,
      ObjectMapper mapper,
      TimetableCatalog catalog,
      TimetableImportStore store) {
    return ignored -> {
      Path root = requiredAbsolutePath(environment, "timing-jeju.timetable-import.root");
      Path source =
          root.resolve(environment.getRequiredProperty("timing-jeju.timetable-import.file"))
              .normalize();
      Path mappingPath =
          requiredAbsolutePath(environment, "timing-jeju.timetable-import.mapping-file");
      var mapping = new OperatorTimetableMappingLoader(mapper).load(mappingPath);
      byte[] xlsx = new SafeTimetableFileReader(root).read(source);
      boolean dryRun =
          environment.getProperty("timing-jeju.timetable-import.dry-run", Boolean.class, true);
      String idempotencyKey =
          environment.getRequiredProperty("timing-jeju.timetable-import.idempotency-key");
      var parser =
          new MappedTimetableParser(
              new JejuTimetableXlsxParser(), Map.of(mapping.scheduleId(), mapping));
      var result =
          new JejuTimetableImportService(parser, catalog, store)
              .importXlsx(
                  new TimetableImportCommand(
                      xlsx,
                      mapping.scheduleId(),
                      mapping.effectiveDate(),
                      Instant.now(),
                      dryRun,
                      idempotencyKey));
      log.info("{}", TimetableImportReportFormatter.format(mapping.scheduleId(), dryRun, result));
    };
  }

  private static Path requiredAbsolutePath(Environment environment, String property) {
    Path path = Path.of(environment.getRequiredProperty(property));
    if (!path.isAbsolute()) throw new IllegalArgumentException(property + " must be absolute");
    return path.normalize();
  }
}
