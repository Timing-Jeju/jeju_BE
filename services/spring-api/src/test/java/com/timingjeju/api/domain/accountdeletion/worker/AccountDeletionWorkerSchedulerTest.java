package com.timingjeju.api.domain.accountdeletion.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.accountdeletion.worker.adapter.AccountDeletionWorkerScheduler;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AccountDeletionWorkerSchedulerTest {
  @Test
  void scheduler_tick은_worker_command를_정확히_한번_호출한다() {
    AtomicInteger calls = new AtomicInteger();
    AccountDeletionWorkerScheduler scheduler =
        new AccountDeletionWorkerScheduler(calls::incrementAndGet);

    scheduler.tick();

    assertThat(calls).hasValue(1);
  }
}
