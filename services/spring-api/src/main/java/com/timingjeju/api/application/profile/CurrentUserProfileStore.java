package com.timingjeju.api.application.profile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CurrentUserProfileStore {

  Optional<CurrentUserProfile> read(UUID userId);

  CurrentUserProfile update(UUID userId, ProfilePatchCommand command, Instant updatedAt);
}
