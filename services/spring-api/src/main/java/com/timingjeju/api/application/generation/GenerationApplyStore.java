package com.timingjeju.api.application.generation;

import java.time.Instant;
import java.util.UUID;

/** 멱등성 트랜잭션 안에서 후보 소유권과 적용 조건을 검사하고 활성 버전을 원자 변경한다. */
public interface GenerationApplyStore {
  void requireOwned(UUID owner, UUID trip, UUID run, UUID candidate);

  Applied apply(
      UUID owner,
      UUID trip,
      UUID run,
      UUID candidate,
      long expectedRevision,
      UUID expectedActiveVersion,
      Instant requestedAt);

  record Applied(
      UUID tripId,
      UUID runId,
      UUID candidateId,
      UUID previousScheduleVersionId,
      UUID activeScheduleVersionId,
      long tripRevision,
      Instant appliedAt) {}
}
