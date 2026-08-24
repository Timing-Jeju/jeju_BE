package com.timingjeju.api.global.retention;

import com.timingjeju.api.application.retention.SnapshotRetentionCycleCommand;
import com.timingjeju.api.application.retention.SnapshotRetentionOrchestrator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class SnapshotRetentionScheduler {
  private static final Logger log = LoggerFactory.getLogger(SnapshotRetentionScheduler.class);
  private final SnapshotRetentionOrchestrator orchestrator;
  private final SnapshotRetentionCycleCommand command;
  private final AtomicBoolean running = new AtomicBoolean();

  SnapshotRetentionScheduler(
      SnapshotRetentionOrchestrator orchestrator, SnapshotRetentionCycleCommand command) {
    this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator는 필수입니다.");
    this.command = Objects.requireNonNull(command, "command는 필수입니다.");
  }

  @Scheduled(
      fixedDelayString = "${app.snapshot-retention.schedule.fixed-delay:PT24H}",
      initialDelayString = "${app.snapshot-retention.schedule.initial-delay:PT1M}")
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      orchestrator.execute(command);
    } catch (RuntimeException exception) {
      log.error("snapshot_retention scheduled cycle failed");
    } finally {
      running.set(false);
    }
  }
}
