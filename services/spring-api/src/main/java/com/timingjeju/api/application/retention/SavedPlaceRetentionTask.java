package com.timingjeju.api.application.retention;

public interface SavedPlaceRetentionTask {
  int drain(int maxBatches);
}
