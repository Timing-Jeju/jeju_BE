package com.timingjeju.api.domain.accountdeletion.adapter;

import static org.mockito.Mockito.verify;

import com.timingjeju.api.domain.accountdeletion.repository.AccountDeletionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@Tag("unit")
class AccountDeletionSecretCleanupSchedulerTest {
  @Test
  void tick은_한번에_제한된_batch만_cleanup한다() {
    AccountDeletionRepository repository = Mockito.mock(AccountDeletionRepository.class);
    Instant now = Instant.parse("2026-09-14T00:00:00Z");
    var scheduler =
        new AccountDeletionSecretCleanupScheduler(
            repository, Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(24), 100);

    scheduler.tick();

    verify(repository).clearExpiredSecrets(now, Duration.ofHours(24), 100);
  }
}
