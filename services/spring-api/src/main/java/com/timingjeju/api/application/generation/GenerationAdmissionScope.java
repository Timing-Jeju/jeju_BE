package com.timingjeju.api.application.generation;

import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.trip.TripException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** DB adapter가 여행 잠금을 보유한 접수 transaction 안에서 읽고 검증하는 범위다. */
public record GenerationAdmissionScope(
    UUID ownerId, UUID tripId, long revision, UUID activeScheduleVersionId, List<UUID> dayIds) {
  public GenerationAdmissionScope {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    dayIds = List.copyOf(dayIds);
    if (revision < 1) {
      throw new IllegalArgumentException("여행 revision은 양수여야 합니다.");
    }
  }

  public void validate(
      UUID requestingOwnerId,
      UUID requestedTripId,
      long expectedRevision,
      CreateGenerationCommand command) {
    Objects.requireNonNull(command);
    if (!ownerId.equals(requestingOwnerId) || !tripId.equals(requestedTripId)) {
      throw TripException.notFound();
    }
    if (!dayIds.contains(command.targetDayId())) {
      throw GenerationException.inputConstraintViolation();
    }
    if (revision != expectedRevision) {
      throw TripException.versionConflict();
    }
    if (!Objects.equals(activeScheduleVersionId, command.expectedActiveScheduleVersionId())) {
      throw ScheduleException.activeVersionConflict();
    }
  }
}
