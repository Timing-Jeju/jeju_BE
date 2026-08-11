package com.timingjeju.api.application.asyncrun;

import java.time.Instant;

@FunctionalInterface
public interface AsyncRunExecutor {

  RunResultSource execute(RunLease lease, Instant deadline);
}
