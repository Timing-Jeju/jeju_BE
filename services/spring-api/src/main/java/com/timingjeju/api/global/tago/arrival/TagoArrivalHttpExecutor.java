package com.timingjeju.api.global.tago.arrival;

@FunctionalInterface
interface TagoArrivalHttpExecutor {
  byte[] execute(TagoArrivalHttpRequest request);
}
