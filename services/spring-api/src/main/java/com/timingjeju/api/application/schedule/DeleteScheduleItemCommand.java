package com.timingjeju.api.application.schedule;

import java.util.Objects;
import java.util.UUID;

public record DeleteScheduleItemCommand(UUID expectedActiveScheduleVersionId) {
  public DeleteScheduleItemCommand {
    Objects.requireNonNull(expectedActiveScheduleVersionId);
  }
}
