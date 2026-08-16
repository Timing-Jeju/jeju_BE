package com.timingjeju.api.global.tourapi.image;

@FunctionalInterface
interface DetailImageHttpExecutor {
  byte[] execute(DetailImageHttpRequest request);
}
