package com.timingjeju.api.domain.accountdeletion.adapter;

import com.timingjeju.api.domain.accountdeletion.repository.AccountDeletionRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class AccountDeletionSecretCleanupScheduler {
  private static final Logger log =
      LoggerFactory.getLogger(AccountDeletionSecretCleanupScheduler.class);
  private final AccountDeletionRepository repository;
  private final Clock clock;
  private final Duration terminalRetention;
  private final int batchSize;
  private final AtomicBoolean running = new AtomicBoolean();

  public AccountDeletionSecretCleanupScheduler(
      AccountDeletionRepository repository,
      Clock clock,
      Duration terminalRetention,
      int batchSize) {
    this.repository = Objects.requireNonNull(repository);
    this.clock = Objects.requireNonNull(clock);
    this.terminalRetention = Objects.requireNonNull(terminalRetention);
    this.batchSize = batchSize;
  }

  @Scheduled(
      fixedDelayString = "${app.account-deletion.secret-cleanup.fixed-delay:PT1H}",
      initialDelayString = "${app.account-deletion.secret-cleanup.initial-delay:PT5M}")
  public void tick() {
    if (!running.compareAndSet(false, true)) return;
    try {
      repository.clearExpiredSecrets(clock.instant(), terminalRetention, batchSize);
    } catch (RuntimeException failure) {
      log.error("security_event=account_deletion_secret_cleanup_failed");
    } finally {
      running.set(false);
    }
  }
}
