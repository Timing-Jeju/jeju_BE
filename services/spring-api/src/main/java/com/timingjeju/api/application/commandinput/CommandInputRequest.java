package com.timingjeju.api.application.commandinput;

import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CommandInputRequest(
    CommandInputParent parent,
    String runType,
    int schemaVersion,
    String contractVersion,
    String algorithmVersion,
    JsonNode structuredInput,
    UUID ownerUserId,
    UUID tripPlanId,
    UUID baseScheduleVersionId,
    CommandLocation location) {

  public CommandInputRequest {
    Objects.requireNonNull(parent, "parent는 필수입니다.");
    requireText(runType, "runType은 필수입니다.");
    requireText(contractVersion, "contractVersion은 필수입니다.");
    requireText(algorithmVersion, "algorithmVersion은 필수입니다.");
    Objects.requireNonNull(structuredInput, "structuredInput은 필수입니다.");
    Objects.requireNonNull(ownerUserId, "ownerUserId는 필수입니다.");
    Objects.requireNonNull(tripPlanId, "tripPlanId는 필수입니다.");
  }

  private static void requireText(String value, String message) {
    if (value == null || value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException(message);
    }
  }
}
