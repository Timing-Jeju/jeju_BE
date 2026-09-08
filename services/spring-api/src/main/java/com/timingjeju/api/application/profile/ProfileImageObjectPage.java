package com.timingjeju.api.application.profile;

import java.util.List;

public record ProfileImageObjectPage(
    List<String> objectKeys, ProfileImageScanCursor nextCursor, boolean cycleComplete) {

  public ProfileImageObjectPage {
    objectKeys = List.copyOf(objectKeys);
    java.util.Objects.requireNonNull(nextCursor);
  }
}
