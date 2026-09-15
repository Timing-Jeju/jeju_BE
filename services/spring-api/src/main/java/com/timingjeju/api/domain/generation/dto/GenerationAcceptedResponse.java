package com.timingjeju.api.domain.generation.dto;

import com.timingjeju.api.application.generation.GenerationAccepted;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
    requiredProperties = {
      "contractVersion",
      "runId",
      "status",
      "pollUrl",
      "commandInputHash",
      "acceptedAt"
    })
public record GenerationAcceptedResponse(
    @Schema(allowableValues = {"1.0.0"}) String contractVersion,
    UUID runId,
    @Schema(allowableValues = {"queued"}) String status,
    String pollUrl,
    @Schema(pattern = "^[0-9a-f]{64}$") String commandInputHash,
    OffsetDateTime acceptedAt) {
  public static GenerationAcceptedResponse from(GenerationAccepted value) {
    return new GenerationAcceptedResponse(
        value.contractVersion(),
        value.runId(),
        value.status(),
        value.pollUrl(),
        value.commandInputHash(),
        value.acceptedAt());
  }
}
