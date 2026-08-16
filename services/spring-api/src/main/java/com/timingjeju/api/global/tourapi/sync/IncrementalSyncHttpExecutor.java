package com.timingjeju.api.global.tourapi.sync;

@FunctionalInterface
interface IncrementalSyncHttpExecutor {
  byte[] execute(IncrementalSyncHttpRequest request);
}
