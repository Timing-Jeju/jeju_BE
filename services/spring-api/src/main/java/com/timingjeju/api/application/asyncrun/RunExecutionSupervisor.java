package com.timingjeju.api.application.asyncrun;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public interface RunExecutionSupervisor {

  CompletableFuture<Void> supervise(
      RunLease lease,
      Instant deadline,
      Duration heartbeatInterval,
      AsyncRunExecutor executor,
      BooleanSupplier heartbeat);

  void shutdown(Duration drainTimeout);
}
