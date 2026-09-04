package com.timingjeju.api.application.commandinput.cleanup;

public enum CommandLocationCleanupCycleOutcome {
  SUCCESS,
  BOUNDED_BATCHES,
  BOUNDED_DURATION,
  FAILED,
  INTERRUPTED
}
