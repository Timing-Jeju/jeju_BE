package com.timingjeju.api.application.profile;

import java.util.UUID;
import java.util.function.Supplier;

@FunctionalInterface
public interface ProfileImageMutationExecutor {
  ProfileImageApplyResult execute(
      UUID ownerId,
      String idempotencyKey,
      ProfileImageCommand command,
      Supplier<ProfileImageSnapshot> mutation);
}
