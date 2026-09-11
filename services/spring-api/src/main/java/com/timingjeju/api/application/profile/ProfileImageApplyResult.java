package com.timingjeju.api.application.profile;

import java.util.Objects;

public record ProfileImageApplyResult(ProfileImageSnapshot snapshot, boolean replayed) {
  public ProfileImageApplyResult {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
  }
}
