package com.timingjeju.api.domain.accountdeletion.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AccountDeletionWorkRepository {

  List<DeletionLease> claimAvailable(String owner, Instant now, Duration leaseDuration, int limit);

  Optional<DeletionWork> load(DeletionLease lease);

  boolean heartbeat(DeletionLease lease, Instant now, Duration leaseDuration);

  boolean startStep(DeletionLease lease, DeletionStep step, Instant startedAt);

  boolean completeStep(DeletionLease lease, DeletionStep step, Instant completedAt);

  boolean completeAuthDeletionAndClearSubject(DeletionLease lease, Instant completedAt);

  boolean succeed(DeletionLease lease, Instant completedAt);

  boolean confirmCancelled(DeletionLease lease, Instant completedAt);

  boolean retry(DeletionLease lease, String failureCode, Instant nextRetryAt, Instant failedAt);

  boolean fail(DeletionLease lease, String failureCode, Instant failedAt);
}
