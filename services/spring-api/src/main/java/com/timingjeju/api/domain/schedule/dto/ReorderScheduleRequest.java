package com.timingjeju.api.domain.schedule.dto;

import com.timingjeju.api.application.schedule.ReorderScheduleCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(
    name = "ReorderScheduleRequest",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReorderScheduleRequest(
    UUID expectedActiveScheduleVersionId, List<DayOrderRequest> days) {
  public ReorderScheduleCommand toCommand() {
    return new ReorderScheduleCommand(
        expectedActiveScheduleVersionId,
        days == null ? null : days.stream().map(DayOrderRequest::toCommand).toList());
  }

  public record DayOrderRequest(Integer dayNo, List<UUID> orderedItemIds) {
    ReorderScheduleCommand.DayOrder toCommand() {
      return new ReorderScheduleCommand.DayOrder(dayNo == null ? 0 : dayNo, orderedItemIds);
    }
  }
}
