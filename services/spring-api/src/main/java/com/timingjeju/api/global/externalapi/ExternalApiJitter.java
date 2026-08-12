package com.timingjeju.api.global.externalapi;

import java.util.concurrent.ThreadLocalRandom;

@FunctionalInterface
interface ExternalApiJitter {
  long nextLong(long exclusiveUpperBound);

  static ExternalApiJitter threadLocal() {
    return upperBound -> ThreadLocalRandom.current().nextLong(upperBound);
  }
}
