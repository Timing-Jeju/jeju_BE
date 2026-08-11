package com.timingjeju.api.application.asyncrun;

import java.time.Instant;
import java.util.List;

public interface RunLeaseRepository {

  List<RunLease> claimAvailable(String workerId, Instant now, Instant leaseUntil, int limit);

  boolean heartbeat(RunLease lease, Instant now, Instant leaseUntil);

  boolean succeed(RunLease lease, Instant completedAt);

  boolean retry(RunLease lease, Instant nextAttemptAt, String stableErrorCode);

  boolean fail(RunLease lease, Instant completedAt, String stableErrorCode);
}
