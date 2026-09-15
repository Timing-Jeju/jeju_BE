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
  private final GenerationDayHistoryRepository history;

  public McpGenerationExecutor(
      GenerationTripInputRepository snapshots,
      CommandInputSnapshotRepository commands,
      GenerationPlaceResolver places,
      McpToolClient client,
      ObjectMapper mapper,
      Clock clock,
      Set<String> approvedSources,
      GenerationDayHistoryRepository history) {
    this.snapshots = Objects.requireNonNull(snapshots);
    this.commands = Objects.requireNonNull(commands);
    this.places = Objects.requireNonNull(places);
    this.client = Objects.requireNonNull(client);
    this.mapper = Objects.requireNonNull(mapper);
    this.clock = Objects.requireNonNull(clock);
    this.approvedSources = Set.copyOf(approvedSources);
    this.history = Objects.requireNonNull(history);
  }

  @Override
  public GenerationCandidateProjection execute(UUID runId, Instant deadline) {
    try {
      return executeSaved(runId, deadline);
    } catch (McpContractException failure) {
      throw GenerationException.invalidResult();
    } catch (McpRemoteCallException failure) {
      var sanitized = GenerationException.mcpFailure(failure.stableCode());
      if (failure.retryable()
          && Set.of("MCP_TIMEOUT", "MCP_TRANSPORT_UNAVAILABLE").contains(sanitized.code()))
        throw new RetryableRunException(sanitized.code());
      throw sanitized;
    }
  }

  private GenerationCandidateProjection executeSaved(UUID runId, Instant deadline) {
    checkDeadline(deadline);
    var snapshot = snapshots.find(runId).orElseThrow(GenerationException::inputUnavailable);
    var command =
        commands
            .find(new CommandInputParent.Generation(runId))
            .orElseThrow(GenerationException::inputUnavailable);
    validateSnapshots(runId, snapshot, command);
    var input = snapshot.input();
    var previousDays = history.findPrevious(input);
    var visited = input.validatePreviousDays(previousDays);
    var requested = new HashSet<UUID>();
    requested.add(input.boundary().startPlaceId());
    requested.add(input.boundary().endPlaceId());
    input.places().forEach(place -> requested.add(place.placeId()));
    var bindings = places.resolve(requested, clock.instant());
    var request = new java.util.LinkedHashMap<>(GenerationMcpDayConditions.from(input, bindings));
    request.put("previous_days", previousDays.stream().map(GenerationSelectedDay::toMcp).toList());
    var allowedPlaceIds = new HashSet<>(bindings.factIds());
    var previousFactIds = new HashSet<String>();
    for (var day : previousDays) {
      day.selectedPlaces().forEach(place -> allowedPlaceIds.add(place.placeFactId()));
      previousFactIds.addAll(day.evidenceFactIds());
    }
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
                    allowedPlaceIds,
                    "evidence_fact_ids",
                    previousFactIds,
                    "derivation_evidence_fact_ids",
                    previousFactIds),
                Map.of()),
            content -> {
              var response = mapper.valueToTree(content);
              GenerationEntranceEvidence.from(response, approvedSources);
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
    var projection = result.projection();
    if (projection.candidates().stream()
        .anyMatch(
            candidate ->
                candidate.history().selectedPlaces().stream()
                    .anyMatch(place -> visited.contains(place.canonicalPlaceId()))))
      return new GenerationCandidateProjection(
          projection.factsAsOf(),
          "insufficient_feasible_routes",
          java.util.List.of(),
          new GenerationEvidence(Map.of(), Set.of()));
    return projection;
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
