package com.timingjeju.api.application.notification;

import java.time.Instant;
import java.util.UUID;

public interface PushDeviceStore {

  PushDevice register(
      UUID userId, UUID deviceId, ProtectedPushDeviceRegistration registration, Instant observedAt);

  void invalidate(UUID userId, UUID deviceId, Instant invalidatedAt);
}
