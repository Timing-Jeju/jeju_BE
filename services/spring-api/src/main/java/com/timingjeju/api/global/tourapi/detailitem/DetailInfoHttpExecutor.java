package com.timingjeju.api.global.tourapi.detailitem;

@FunctionalInterface
interface DetailInfoHttpExecutor {
  byte[] execute(DetailInfoHttpRequest request);
}
