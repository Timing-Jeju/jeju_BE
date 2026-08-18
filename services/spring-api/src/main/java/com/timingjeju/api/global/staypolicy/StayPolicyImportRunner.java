package com.timingjeju.api.global.staypolicy;

import com.timingjeju.api.application.staypolicy.StayPolicyImportService;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.stay-policy.import", name = "enabled", havingValue = "true")
final class StayPolicyImportRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(StayPolicyImportRunner.class);

  private final Environment environment;
  private final StayPolicyCsvImportCommand command;

  StayPolicyImportRunner(Environment environment, StayPolicyImportService importService) {
    this.environment = environment;
    this.command = new StayPolicyCsvImportCommand(importService);
  }

  @Override
  public void run(ApplicationArguments arguments) {
    StayPolicyImportOptions options =
        new StayPolicyImportOptions(
            Path.of(required("app.stay-policy.import.root")),
            Path.of(required("app.stay-policy.import.file")),
            required("app.stay-policy.import.version"),
            Instant.parse(required("app.stay-policy.import.effective-at")),
            optional("app.stay-policy.import.expected-active-version"),
            strictBoolean("app.stay-policy.import.dry-run"));
    var result = command.execute(options);
    log.info(
        "Stay policy import completed: version={}, payloadHash={}, policies={}, dryRun={}",
        result.version(),
        result.payloadHash(),
        result.importedPolicyCount(),
        result.dryRun());
  }

  private String required(String name) {
    String value = environment.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new StayPolicyFileException("Required stay policy import setting is missing: " + name);
    }
    return value;
  }

  private String optional(String name) {
    String value = environment.getProperty(name);
    return value == null || value.isBlank() ? null : value;
  }

  private boolean strictBoolean(String name) {
    String value = required(name);
    if (!"true".equals(value) && !"false".equals(value)) {
      throw new StayPolicyFileException(name + " must be exactly true or false");
    }
    return Boolean.parseBoolean(value);
  }
}
