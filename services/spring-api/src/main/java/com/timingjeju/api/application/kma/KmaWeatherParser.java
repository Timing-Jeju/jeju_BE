package com.timingjeju.api.application.kma;

@FunctionalInterface
public interface KmaWeatherParser {
  KmaWeatherBatch parse(KmaWeatherOperation operation, byte[] payload);
}
