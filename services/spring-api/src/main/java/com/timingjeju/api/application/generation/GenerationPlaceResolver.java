package com.timingjeju.api.application.generation;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface GenerationPlaceResolver {
  GenerationPlaceBindings resolve(Set<UUID> canonicalIds, Instant now);
}
