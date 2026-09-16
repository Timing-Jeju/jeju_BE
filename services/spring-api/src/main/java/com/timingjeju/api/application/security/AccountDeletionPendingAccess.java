package com.timingjeju.api.application.security;

import java.util.UUID;

@FunctionalInterface
public interface AccountDeletionPendingAccess {
  boolean isPending(UUID userId);
}
