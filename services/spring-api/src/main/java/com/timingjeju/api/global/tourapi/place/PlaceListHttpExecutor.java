package com.timingjeju.api.global.tourapi.place;

@FunctionalInterface
interface PlaceListHttpExecutor {
  byte[] execute(PlaceListHttpRequest request);
}
