package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.service.ProfileImageCleanupService;
import com.timingjeju.api.application.profile.service.ProfileImageOrphanService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

final class ProfileImageMaintenanceRunner {

  private static final Logger log = LoggerFactory.getLogger(ProfileImageMaintenanceRunner.class);

  private final ProfileImageCleanupService cleanup;
  private final ProfileImageOrphanService orphan;
  private final AtomicBoolean running = new AtomicBoolean();

  ProfileImageMaintenanceRunner(
      ProfileImageCleanupService cleanup, ProfileImageOrphanService orphan) {
    this.cleanup = Objects.requireNonNull(cleanup);
    this.orphan = Objects.requireNonNull(orphan);
  }

  @Scheduled(
      fixedDelayString = "${app.profile-image.maintenance.cleanup-fixed-delay:PT1M}",
      initialDelayString = "${app.profile-image.maintenance.initial-delay:PT1M}")
  public void cleanupTick() {
    run("profile_image_cleanup", cleanup::runOnce);
  }

  @Scheduled(
      fixedDelayString = "${app.profile-image.maintenance.orphan-fixed-delay:PT24H}",
      initialDelayString = "${app.profile-image.maintenance.initial-delay:PT1M}")
  public void orphanTick() {
    run("profile_image_orphan", orphan::scanOnce);
  }

  private void run(String operation, Cycle cycle) {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      cycle.run();
    } catch (RuntimeException failure) {
      log.error("{} scheduled cycle failed", operation);
    } finally {
      running.set(false);
    }
  }

  @FunctionalInterface
  private interface Cycle {
    int run();
  }
}
