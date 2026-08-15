package com.timingjeju.api.global.tourapi.detail;

@FunctionalInterface
interface PlaceDetailHttpExecutor {
  byte[] execute(PlaceDetailHttpRequest request);
}
