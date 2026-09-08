package com.timingjeju.api.application.profile;

public interface ProfileImageOrphanScanCursorStore {

  ProfileImageScanCursor load();

  boolean advance(ProfileImageScanCursor expected, ProfileImageScanCursor next);
}
