package com.timingjeju.api.domain.accountdeletion.port;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface RecentAuthSessionGateway {
  boolean isRecent(UUID userId, UUID sessionId, Instant notBefore);
}
