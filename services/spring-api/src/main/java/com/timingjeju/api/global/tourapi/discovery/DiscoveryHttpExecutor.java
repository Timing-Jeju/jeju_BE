package com.timingjeju.api.global.tourapi.discovery;

@FunctionalInterface
interface DiscoveryHttpExecutor {
  byte[] execute(DiscoveryHttpRequest request);
}
