package com.timingjeju.api.application.generation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 생성 생명주기 읽기 경계. 외부 호출·상태 변경·원문 복원을 하지 않는다. */
public interface GenerationRunReader {
  Optional<SavedRun> findOwned(UUID owner, UUID trip, UUID run);

  record SavedRun(
      UUID runId,
      UUID tripId,
      UUID targetDayId,
      UUID baseScheduleVersionId,
      String status,
      String outcome,
      String commandInputHash,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt,
      Instant retainedUntil,
      Instant factsAsOf,
      boolean stale,
      GenerationFailure failure,
      List<SavedCandidate> candidates) {
    public SavedRun {
      candidates = List.copyOf(candidates);
    }

    public SavedRun withCandidates(List<SavedCandidate> values) {
      return new SavedRun(
          runId,
          tripId,
          targetDayId,
          baseScheduleVersionId,
          status,
          outcome,
          commandInputHash,
          createdAt,
          startedAt,
          completedAt,
          retainedUntil,
          factsAsOf,
          stale,
          failure,
          values);
    }
  }

  record SavedCandidate(
      UUID candidateId,
      UUID scheduleVersionId,
      int rank,
      String strategy,
      BigDecimal score,
      String feasibility,
      String explanation,
      Instant createdAt,
      Instant expiresAt) {}
}
