package com.timingjeju.api.application.profile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface ProfileImageStore {

  Optional<ProfileImageState> read(UUID ownerId);

  ProfileImageState confirm(
      UUID ownerId,
      long expectedVersion,
      ProfileImageMetadata metadata,
      Supplier<ProfileImageMetadata> lockedMetadataRecheck,
      Instant changedAt);

  ProfileImageState clear(UUID ownerId, long expectedVersion, Instant changedAt);
}
