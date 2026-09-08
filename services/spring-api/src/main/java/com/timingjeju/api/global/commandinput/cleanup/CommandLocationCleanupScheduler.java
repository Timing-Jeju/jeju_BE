package com.timingjeju.api.global.commandinput.cleanup;

import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupCycleCommand;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupOrchestrator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class CommandLocationCleanupScheduler {
  private static final Logger log = LoggerFactory.getLogger(CommandLocationCleanupScheduler.class);
  private final CommandLocationCleanupOrchestrator orchestrator;
  private final CommandLocationCleanupCycleCommand command;
  private final AtomicBoolean running = new AtomicBoolean();

  CommandLocationCleanupScheduler(
      CommandLocationCleanupOrchestrator orchestrator, CommandLocationCleanupCycleCommand command) {
    this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator는 필수입니다.");
    this.command = Objects.requireNonNull(command, "command는 필수입니다.");
  }

  @Scheduled(
      fixedDelayString = "${app.command-location-cleanup.schedule.fixed-delay:PT5M}",
      initialDelayString = "${app.command-location-cleanup.schedule.initial-delay:PT1M}")
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      orchestrator.execute(command);
    } catch (RuntimeException failure) {
      log.error("command_location_cleanup scheduled cycle failed");
    } finally {
      running.set(false);
    }
  }
}
