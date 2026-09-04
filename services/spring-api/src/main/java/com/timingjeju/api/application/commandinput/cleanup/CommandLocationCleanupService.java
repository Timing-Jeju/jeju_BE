package com.timingjeju.api.application.commandinput.cleanup;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public class CommandLocationCleanupService {
  private final CommandLocationCleanupPort port;
  private final Clock clock;

  public CommandLocationCleanupService(CommandLocationCleanupPort port, Clock clock) {
    this.port = Objects.requireNonNull(port, "port는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
  }

  public CommandLocationCleanupResult execute(int batchSize) {
    return execute(batchSize, Duration.ofSeconds(30));
  }

  public CommandLocationCleanupResult execute(int batchSize, Duration attemptTimeout) {
    return port.execute(
        new CommandLocationCleanupCommand(
            clock.instant().truncatedTo(ChronoUnit.MICROS), batchSize, attemptTimeout));
  }
}
