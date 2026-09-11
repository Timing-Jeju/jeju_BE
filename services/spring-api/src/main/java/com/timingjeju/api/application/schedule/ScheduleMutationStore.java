package com.timingjeju.api.application.schedule;

public interface ScheduleMutationStore {
  ScheduleMutationResult addItem(ScheduleMutationRecord record);

  ScheduleMutationResult patchItem(ScheduleEditRecord<PatchScheduleItemCommand> record);

  ScheduleMutationResult deleteItem(ScheduleEditRecord<DeleteScheduleItemCommand> record);

  ScheduleMutationResult reorder(ScheduleEditRecord<ReorderScheduleCommand> record);

  ScheduleMutationResult moveItem(ScheduleEditRecord<MoveScheduleItemCommand> record);
}
