package com.timingjeju.api.global.logging;

import java.util.UUID;

public final class UuidTraceIdGenerator implements TraceIdGenerator {

  @Override
  public String generate() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
