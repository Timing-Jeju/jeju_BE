package com.timingjeju.api.domain.generation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.timingjeju.api.application.generation.GenerationFailure;
import com.timingjeju.api.application.generation.GenerationRunReader;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** 소유권·보존 기한·완전성이 검증된 저장 결과의 공개 표현. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    name = "GenerationRunStatus",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
    requiredProperties = {
      "contractVersion",
      "runId",
      "status",
      "pollUrl",
      "commandInputHash",
      "createdAt"
    })
public record GenerationRunResponse(
    @Schema(allowableValues = {"1.0.0"}) String contractVersion,
    UUID runId,
    @Schema(allowableValues = {"queued", "running", "succeeded", "failed", "cancelled"})
        String status,
    String pollUrl,
    @Schema(pattern = "^[0-9a-f]{64}$") String commandInputHash,
    OffsetDateTime createdAt,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    Result result,
    @Schema(implementation = Failure.class) GenerationFailure failure) {

  @Schema(
      name = "GenerationFailure",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
      requiredProperties = {"code", "detail", "retryable"})
  public record Failure(String code, String detail, boolean retryable) {}

  public static GenerationRunResponse from(GenerationRunReader.SavedRun saved) {
    String tripUrl = "/api/v1/trips/" + saved.tripId();
    String pollUrl = tripUrl + "/schedule-generations/" + saved.runId();
    Result result = null;
    if ("succeeded".equals(saved.status())) {
      var candidates =
          saved.candidates().stream()
              .map(
                  candidate ->
                      new Candidate(
                          candidate.candidateId(),
                          candidate.scheduleVersionId(),
                          candidate.rank(),
                          candidate.strategy(),
                          candidate.score(),
                          candidate.feasibility(),
                          candidate.explanation(),
                          seoul(candidate.expiresAt()),
                          tripUrl + "/schedule-versions/" + candidate.scheduleVersionId(),
                          pollUrl + "/candidates/" + candidate.candidateId() + "/apply"))
              .toList();
      result =
          new Result(
              saved.outcome(),
              saved.baseScheduleVersionId(),
              seoul(saved.factsAsOf()),
              saved.stale(),
              "mcp",
              candidates);
    }
    return new GenerationRunResponse(
        "1.0.0",
        saved.runId(),
        saved.status(),
        pollUrl,
        saved.commandInputHash(),
        seoul(saved.createdAt()),
        seoul(saved.startedAt()),
        seoul(saved.completedAt()),
        result,
        saved.failure());
  }

  private static OffsetDateTime seoul(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.ofHours(9));
  }

  @Schema(
      name = "GenerationResult",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
      requiredProperties = {
        "outcome",
        "baseScheduleVersionId",
        "factsAsOf",
        "stale",
        "resultSource",
        "candidates"
      })
  public record Result(
      @Schema(allowableValues = {"success", "insufficient_feasible_routes"}) String outcome,
      @JsonInclude(JsonInclude.Include.ALWAYS)
          @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
          UUID baseScheduleVersionId,
      OffsetDateTime factsAsOf,
      boolean stale,
      @Schema(allowableValues = {"mcp"}) String resultSource,
      List<Candidate> candidates) {
    public Result {
      candidates = List.copyOf(candidates);
    }
  }

  @Schema(
      name = "GenerationCandidate",
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
      requiredProperties = {
        "candidateId",
        "scheduleVersionId",
        "rank",
        "strategy",
        "score",
        "feasibility",
        "explanation",
        "expiresAt",
        "scheduleUrl",
        "applyUrl"
      })
  public record Candidate(
      UUID candidateId,
      UUID scheduleVersionId,
      @Schema(minimum = "1", maximum = "3") int rank,
      @Schema(allowableValues = {"balanced", "relaxed", "experience_max"}) String strategy,
      @Schema(minimum = "0", maximum = "100") BigDecimal score,
      @Schema(allowableValues = {"feasible", "feasible_with_caution"}) String feasibility,
      @Schema(minLength = 1, maxLength = 1000) String explanation,
      OffsetDateTime expiresAt,
      String scheduleUrl,
      String applyUrl) {}
}
