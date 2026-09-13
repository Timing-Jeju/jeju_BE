package com.timingjeju.api.application.generation;

import java.util.UUID;

public record CreateGenerationCommand(
    UUID targetDayId, UUID expectedActiveScheduleVersionId, int candidateCount) {
  public CreateGenerationCommand {
    if (targetDayId == null || candidateCount != 3) {
      throw GenerationException.invalidRequest();
    }
  }
}
