package com.timingjeju.api.application.profile;

public record ProfileImageScanCursor(int ownerOffset, int objectOffset, long revision) {

  public ProfileImageScanCursor {
    if (ownerOffset < 0 || objectOffset < 0 || revision < 0) {
      throw new IllegalArgumentException("profile image scan cursor values must be non-negative");
    }
  }

  public static ProfileImageScanCursor initial() {
    return new ProfileImageScanCursor(0, 0, 0);
  }

  public ProfileImageScanCursor nextOwner() {
    return new ProfileImageScanCursor(Math.addExact(ownerOffset, 1), 0, revision);
  }

  public ProfileImageScanCursor nextObjects(int count) {
    return new ProfileImageScanCursor(ownerOffset, Math.addExact(objectOffset, count), revision);
  }

  public ProfileImageScanCursor wrap() {
    return new ProfileImageScanCursor(0, 0, Math.addExact(revision, 1));
  }
}
