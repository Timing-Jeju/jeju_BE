package com.timingjeju.api.global.retention;

import com.timingjeju.api.application.retention.SavedPlaceRetentionTask;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class SavedPlaceRetentionScheduler {
  private static final Logger log = LoggerFactory.getLogger(SavedPlaceRetentionScheduler.class);
  private final SavedPlaceRetentionTask task;
  private final int maxBatches;
  private final AtomicBoolean running = new AtomicBoolean();

  SavedPlaceRetentionScheduler(SavedPlaceRetentionTask task, int maxBatches) {
    this.task = Objects.requireNonNull(task, "saved-place retention task는 필수입니다.");
    this.maxBatches = maxBatches;
  }

  @Scheduled(
      fixedDelayString = "${app.saved-place-retention.fixed-delay:PT24H}",
      initialDelayString = "${app.saved-place-retention.initial-delay:PT1M}")
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      task.drain(maxBatches);
    } catch (RuntimeException exception) {
      log.error("saved_place_retention scheduled cycle failed");
    } finally {
      running.set(false);
    }
  }
}
