package com.timingjeju.api.application.generation;

import java.time.Instant;
import java.util.UUID;

public interface GenerationIntakeStore {
  void requireOwned(UUID ownerId, UUID tripId);

  GenerationAccepted accept(
      UUID ownerId, UUID tripId, long revision, CreateGenerationCommand command, Instant now);
}
