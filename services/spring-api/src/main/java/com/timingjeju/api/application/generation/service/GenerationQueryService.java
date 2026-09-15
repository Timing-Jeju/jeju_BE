package com.timingjeju.api.application.generation.service;

import com.timingjeju.api.application.generation.GenerationException;
import com.timingjeju.api.application.generation.GenerationRunReader;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 소유권 은닉 후 조회 보존 기한을 판정한다. 후보 적용 기한이나 run 상태를 변경하지 않는다. */
public final class GenerationQueryService {
  private final GenerationRunReader reader;
  private final Clock clock;

  public GenerationQueryService(GenerationRunReader reader, Clock clock) {
    this.reader = Objects.requireNonNull(reader);
    this.clock = Objects.requireNonNull(clock);
  }

  public GenerationRunReader.SavedRun read(UUID owner, UUID trip, UUID run) {
    var saved = reader.findOwned(owner, trip, run).orElseThrow(GenerationException::runNotFound);
    if (!trip.equals(saved.tripId()) || !run.equals(saved.runId()))
      throw GenerationException.runNotFound();
    if (Set.of("queued", "running").contains(saved.status())) {
      if (saved.completedAt() != null || saved.retainedUntil() != null)
        throw GenerationException.resultUnavailable();
    } else if (Set.of("succeeded", "failed", "cancelled").contains(saved.status())) {
      if (saved.completedAt() == null
          || saved.retainedUntil() == null
          || !saved.completedAt().plus(Duration.ofDays(7)).equals(saved.retainedUntil()))
        throw GenerationException.resultUnavailable();
      if (!clock.instant().isBefore(saved.retainedUntil()))
        throw GenerationException.resultExpired();
    } else {
      throw GenerationException.resultUnavailable();
    }
    validateCandidates(saved);
    return saved;
  }

  private static void validateCandidates(GenerationRunReader.SavedRun saved) {
    var candidates = saved.candidates();
    if (!"succeeded".equals(saved.status())) {
      if (saved.outcome() != null || !candidates.isEmpty() || saved.factsAsOf() != null)
        throw GenerationException.resultUnavailable();
      return;
    }
    if (saved.factsAsOf() == null || saved.factsAsOf().isAfter(saved.completedAt()))
      throw GenerationException.resultUnavailable();
    if ("insufficient_feasible_routes".equals(saved.outcome())) {
      if (!candidates.isEmpty()) throw GenerationException.resultUnavailable();
      return;
    }
    if (!"success".equals(saved.outcome()) || candidates.size() != 3)
      throw GenerationException.resultUnavailable();
    var ids = new java.util.HashSet<UUID>();
    var versions = new java.util.HashSet<UUID>();
    var ranks = new java.util.HashSet<Integer>();
    var strategies = new java.util.HashSet<String>();
    for (var candidate : candidates) {
      if (candidate.candidateId() == null
          || !ids.add(candidate.candidateId())
          || candidate.scheduleVersionId() == null
          || !versions.add(candidate.scheduleVersionId())
          || candidate.rank() < 1
          || candidate.rank() > 3
          || !ranks.add(candidate.rank())
          || candidate.strategy() == null
          || !strategies.add(candidate.strategy())
          || candidate.score() == null
          || candidate.score().signum() < 0
          || candidate.score().compareTo(java.math.BigDecimal.valueOf(100)) > 0
          || candidate.feasibility() == null
          || !Set.of("feasible", "feasible_with_caution").contains(candidate.feasibility())
          || candidate.explanation() == null
          || candidate.explanation().isBlank()
          || candidate.explanation().length() > 1000
          || candidate.createdAt() == null
          || candidate.expiresAt() == null
          || !candidate.createdAt().plus(Duration.ofHours(24)).equals(candidate.expiresAt()))
        throw GenerationException.resultUnavailable();
    }
    if (!strategies.equals(Set.of("balanced", "relaxed", "experience_max")))
      throw GenerationException.resultUnavailable();
  }
}
