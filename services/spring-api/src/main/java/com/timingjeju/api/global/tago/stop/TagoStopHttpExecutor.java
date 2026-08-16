package com.timingjeju.api.global.tago.stop;

@FunctionalInterface
interface TagoStopHttpExecutor {
  byte[] execute(TagoStopHttpRequest request);
}
