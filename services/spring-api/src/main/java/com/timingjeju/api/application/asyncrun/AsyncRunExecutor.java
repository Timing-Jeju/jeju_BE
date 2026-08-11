package com.timingjeju.api.application.asyncrun;

import java.time.Instant;

@FunctionalInterface
public interface AsyncRunExecutor {

  void execute(RunLease lease, Instant deadline);
}
