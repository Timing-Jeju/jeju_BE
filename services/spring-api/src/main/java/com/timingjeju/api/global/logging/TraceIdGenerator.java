package com.timingjeju.api.global.logging;

@FunctionalInterface
public interface TraceIdGenerator {

  String generate();
}
