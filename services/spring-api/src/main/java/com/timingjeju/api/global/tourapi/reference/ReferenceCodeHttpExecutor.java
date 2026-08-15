package com.timingjeju.api.global.tourapi.reference;

@FunctionalInterface
interface ReferenceCodeHttpExecutor {
  byte[] execute(ReferenceCodeHttpRequest request);
}
