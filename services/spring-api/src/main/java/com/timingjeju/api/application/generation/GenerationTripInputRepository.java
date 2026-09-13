package com.timingjeju.api.application.generation;

import java.util.Optional;
import java.util.UUID;

public interface GenerationTripInputRepository {
  void save(GenerationTripSnapshot snapshot);

  Optional<GenerationTripSnapshot> find(UUID runId);
}
