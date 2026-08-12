package com.timingjeju.api.global.externalapi;

import java.time.Duration;
import java.time.Instant;

interface ExternalApiTimeSource {
  Instant now();

  long nanoTime();

  void sleep(Duration duration) throws InterruptedException;

  static ExternalApiTimeSource system() {
    return SystemExternalApiTimeSource.INSTANCE;
  }
}

enum SystemExternalApiTimeSource implements ExternalApiTimeSource {
  INSTANCE;

  @Override
  public Instant now() {
    return Instant.now();
  }

  @Override
  public long nanoTime() {
    return System.nanoTime();
  }

  @Override
  public void sleep(Duration duration) throws InterruptedException {
    Thread.sleep(duration);
  }
}
