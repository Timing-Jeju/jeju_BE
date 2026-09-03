package com.timingjeju.api.application.schedule;

public interface ScheduleMutationStore {
  ScheduleMutationResult addItem(ScheduleMutationRecord record);
}
