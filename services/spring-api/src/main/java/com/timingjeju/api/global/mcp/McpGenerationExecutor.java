package com.timingjeju.api.global.mcp;

import com.timingjeju.api.application.asyncrun.RetryableRunException;
import com.timingjeju.api.application.commandinput.*;
import com.timingjeju.api.application.generation.*;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** 저장 스냅샷→canonical 장소→공식 MCP→최소 후보 projection 실행 경계. */
public final class McpGenerationExecutor implements GenerationPlanExecutor {
  private final GenerationTripInputRepository snapshots;
  private final CommandInputSnapshotRepository commands;
  private final GenerationPlaceResolver places;
  private final McpToolClient client;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Set<String> approvedSources;

  public McpGenerationExecutor(
      GenerationTripInputRepository snapshots,
      CommandInputSnapshotRepository commands,
      GenerationPlaceResolver places,
      McpToolClient client,
      ObjectMapper mapper,
      Clock clock,
      Set<String> approvedSources) {
    this.snapshots = Objects.requireNonNull(snapshots);
    this.commands = Objects.requireNonNull(commands);
    this.places = Objects.requireNonNull(places);
    this.client = Objects.requireNonNull(client);
    this.mapper = Objects.requireNonNull(mapper);
    this.clock = Objects.requireNonNull(clock);
    this.approvedSources = Set.copyOf(approvedSources);
  }

  @Override
  public GenerationCandidateProjection execute(UUID runId, Instant deadline) {
    checkDeadline(deadline);
    var snapshot = snapshots.find(runId).orElseThrow(GenerationException::inputUnavailable);
    var command =
        commands
            .find(new CommandInputParent.Generation(runId))
            .orElseThrow(GenerationException::inputUnavailable);
    validateSnapshots(runId, snapshot, command);
    var input = snapshot.input();
    var requested = new HashSet<UUID>();
    requested.add(input.boundary().startPlaceId());
    requested.add(input.boundary().endPlaceId());
    input.places().forEach(place -> requested.add(place.placeId()));
    var bindings = places.resolve(requested, clock.instant());
    var request = GenerationMcpDayConditions.from(input, bindings);
    checkDeadline(deadline);
    var result =
        client.callGeneration(
            new McpInvocation(
                "recommend_jeju_day_trips",
                "generation:" + runId,
                Map.of("request", request),
                command.commandInputHash(),
                McpCallParent.forGenerationRun(runId),
                Map.of(
                    "place_id",
                    bindings.factIds(),
                    "evidence_fact_ids",
                    Set.of(),
                    "derivation_evidence_fact_ids",
                    Set.of()),
                Map.of()),
            content -> {
              var response = mapper.valueToTree(content);
              GenerationEvidence.from(response, approvedSources);
              var recommendations = response.get("recommendations");
              if (recommendations.size() != 3)
                return GenerationCandidateProjection.from(
                    response, input, bindings, approvedSources);
              var resultPlaces = new HashSet<>(bindings.factIds());
              for (var candidate : recommendations)
                for (var place : candidate.get("place_ids")) resultPlaces.add(place.asText());
              var resultBindings = places.resolveFactIds(resultPlaces, clock.instant());
              return GenerationCandidateProjection.from(
                  response, input, resultBindings, approvedSources);
            });
    checkDeadline(deadline);
    return result.projection();
  }

  private void validateSnapshots(
      UUID runId, GenerationTripSnapshot snapshot, CommandInputSnapshot command) {
    var input = snapshot.input();
    if (!runId.equals(snapshot.runId())
        || !new CommandInputParent.Generation(runId).equals(command.parent())
        || !snapshot.ownerId().equals(command.ownerUserId())
        || !input.tripId().equals(command.tripPlanId())
        || !Objects.equals(input.baseScheduleVersionId(), command.baseScheduleVersionId())
        || !"itinerary_generation".equals(command.runType())
        || !"0.7.0".equals(command.contractVersion())
        || !"generation-v1".equals(command.algorithmVersion()))
      throw GenerationException.inputUnavailable();
    try {
      var restored = command.restoreStructuredInput(mapper);
      var checked =
          new CommandInputCanonicalizer(mapper)
              .canonicalize(
                  new CommandInputRequest(
                      command.parent(),
                      command.runType(),
                      command.schemaVersion(),
                      command.contractVersion(),
                      command.algorithmVersion(),
                      restored,
                      command.ownerUserId(),
                      command.tripPlanId(),
                      command.baseScheduleVersionId()));
      if (!checked.commandInputHash().equals(command.commandInputHash())
          || !restored.get("targetDayId").asText().equals(input.boundary().dayId().toString())
          || restored.get("candidateCount").intValue() != 3
          || restored.get("refreshExternalFacts").booleanValue())
        throw GenerationException.inputUnavailable();
    } catch (RuntimeException failure) {
      throw GenerationException.inputUnavailable();
    }
  }

  private void checkDeadline(Instant deadline) {
    if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline))
      throw new RetryableRunException("ASYNC_RUN_DEADLINE_EXCEEDED");
  }
}
