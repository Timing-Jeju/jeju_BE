package com.timingjeju.api.application.commandinput;

import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record CommandInputSnapshot(
    CommandInputParent parent,
    String runType,
    int schemaVersion,
    String contractVersion,
    String algorithmVersion,
    String canonicalStructuredInput,
    String commandInputHash,
    UUID ownerUserId,
    UUID tripPlanId,
    UUID baseScheduleVersionId) {

  public CommandInputSnapshot {
    if (schemaVersion != 2) {
      throw new IllegalArgumentException("지원하지 않는 snapshot schema version입니다.");
    }
    Objects.requireNonNull(parent, "parent는 필수입니다.");
    Objects.requireNonNull(canonicalStructuredInput, "canonical structured input은 필수입니다.");
    if (commandInputHash == null || !commandInputHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("commandInputHash 형식이 올바르지 않습니다.");
    }
  }

  public JsonNode restoreStructuredInput(ObjectMapper objectMapper) {
    Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    try {
      return objectMapper.readTree(canonicalStructuredInput);
    } catch (RuntimeException failure) {
      throw new IllegalStateException("저장된 command input을 복원할 수 없습니다.");
    }
  }
}
