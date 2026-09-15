package com.timingjeju.api.application.generation.service;

import com.timingjeju.api.application.generation.GenerationApplyStore;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class GenerationApplyService {
  private final GenerationApplyStore store;
  private final Clock clock;

  public GenerationApplyService(GenerationApplyStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  public void requireOwned(UUID owner, UUID trip, UUID run, UUID candidate) {
    store.requireOwned(owner, trip, run, candidate);
  }

  public GenerationApplyStore.Applied apply(
      UUID owner, UUID trip, UUID run, UUID candidate, long revision, UUID expectedActive) {
    return store.apply(owner, trip, run, candidate, revision, expectedActive, clock.instant());
  }
}
