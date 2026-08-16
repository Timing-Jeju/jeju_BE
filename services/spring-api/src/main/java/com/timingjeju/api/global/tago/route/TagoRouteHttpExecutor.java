package com.timingjeju.api.global.tago.route;

@FunctionalInterface
interface TagoRouteHttpExecutor {
  byte[] execute(TagoRouteHttpRequest request);
}
