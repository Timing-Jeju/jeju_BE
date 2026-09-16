package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.AccountDeletionWorkerCommand;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class AccountDeletionWorkerScheduler {

  private static final Logger log = LoggerFactory.getLogger(AccountDeletionWorkerScheduler.class);
  private final AccountDeletionWorkerCommand command;
  private final AtomicBoolean running = new AtomicBoolean();

  public AccountDeletionWorkerScheduler(AccountDeletionWorkerCommand command) {
    this.command = Objects.requireNonNull(command, "command는 필수입니다.");
  }

  @Scheduled(
      fixedDelayString = "${app.account-deletion.worker.fixed-delay:PT5S}",
      initialDelayString = "${app.account-deletion.worker.initial-delay:PT10S}")
  public void tick() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      command.pollOnce();
    } catch (RuntimeException failure) {
      log.error("account_deletion_worker poll failed");
    } finally {
      running.set(false);
    }
  }
}
