package com.timingjeju.api.application.profile.service;

import com.timingjeju.api.application.profile.CurrentUserProfile;
import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.CurrentUserProfileStore;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.profile.ProfilePatchCommand;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.util.Objects;

public final class CurrentUserProfileService {

  private final CurrentUserProvisioningService provisioning;
  private final CurrentUserProfileStore profiles;
  private final Clock clock;

  public CurrentUserProfileService(
      CurrentUserProvisioningService provisioning, CurrentUserProfileStore profiles, Clock clock) {
    this.provisioning = Objects.requireNonNull(provisioning, "provisioning must not be null");
    this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public CurrentUserProfile read(CurrentUser currentUser) {
    Objects.requireNonNull(currentUser, "currentUser must not be null");
    provisioning.provision(currentUser);
    return profiles
        .read(currentUser.userId())
        .orElseThrow(CurrentUserProfileException::dataUnavailable);
  }

  public CurrentUserProfile update(CurrentUser currentUser, ProfilePatchCommand command) {
    Objects.requireNonNull(currentUser, "currentUser must not be null");
    Objects.requireNonNull(command, "command must not be null");
    provisioning.provision(currentUser);
    return profiles.update(currentUser.userId(), command, clock.instant());
  }
}
