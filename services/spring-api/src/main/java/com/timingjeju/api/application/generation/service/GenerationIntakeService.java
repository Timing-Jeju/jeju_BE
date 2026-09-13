package com.timingjeju.api.application.generation.service;

import com.timingjeju.api.application.generation.*;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class GenerationIntakeService {
  private final GenerationIntakeStore store;
  private final Clock clock;

  public GenerationIntakeService(GenerationIntakeStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  public void requireOwned(UUID owner, UUID trip) {
    store.requireOwned(owner, trip);
  }

  public GenerationAccepted accept(
      UUID owner, UUID trip, long revision, CreateGenerationCommand command) {
    return store.accept(owner, trip, revision, command, clock.instant());
  }
}
