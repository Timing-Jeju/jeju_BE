package com.timingjeju.api.application.commandinput.cleanup;

@FunctionalInterface
public interface CommandLocationCleanupCycleMetrics {
  void record(CommandLocationCleanupCycleResult result);
}
