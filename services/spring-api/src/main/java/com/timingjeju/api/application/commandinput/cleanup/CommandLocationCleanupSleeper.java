package com.timingjeju.api.application.commandinput.cleanup;

import java.time.Duration;

@FunctionalInterface
public interface CommandLocationCleanupSleeper {
  void sleep(Duration duration) throws InterruptedException;
}
