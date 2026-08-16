package com.timingjeju.api.global.kma;

@FunctionalInterface
interface KmaWeatherHttpExecutor {
  byte[] execute(KmaWeatherHttpRequest request);
}
