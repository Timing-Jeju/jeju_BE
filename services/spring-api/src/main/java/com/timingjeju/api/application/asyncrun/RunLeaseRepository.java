package com.timingjeju.api.application.asyncrun;

import java.time.Duration;
import java.util.List;

public interface RunLeaseRepository {

  List<RunLease> claimAvailable(String workerId, Duration leaseDuration, int limit);

  boolean heartbeat(RunLease lease, Duration leaseDuration);

  boolean succeed(RunLease lease, RunResultSource resultSource);

  boolean retry(RunLease lease, Duration retryDelay, String stableErrorCode);

  boolean fail(RunLease lease, String stableErrorCode);
}
