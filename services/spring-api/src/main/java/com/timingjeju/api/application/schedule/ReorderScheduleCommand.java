package com.timingjeju.api.application.schedule;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record ReorderScheduleCommand(UUID expectedActiveScheduleVersionId, List<DayOrder> days) {
  public ReorderScheduleCommand {
    if (days == null || days.stream().anyMatch(java.util.Objects::isNull)) {
      throw ScheduleException.invalidRequest();
    }
    days = List.copyOf(days);
    var dayNumbers = new HashSet<Integer>();
    if (expectedActiveScheduleVersionId == null
        || days.isEmpty()
        || days.stream().anyMatch(day -> !dayNumbers.add(day.dayNo()))) {
      throw ScheduleException.invalidRequest();
    }
  }

  public record DayOrder(int dayNo, List<UUID> orderedItemIds) {
    public DayOrder {
      if (orderedItemIds == null || orderedItemIds.stream().anyMatch(java.util.Objects::isNull)) {
        throw ScheduleException.invalidRequest();
      }
      orderedItemIds = List.copyOf(orderedItemIds);
      if (dayNo < 1 || orderedItemIds.isEmpty()) {
        throw ScheduleException.invalidRequest();
      }
    }
  }
}
