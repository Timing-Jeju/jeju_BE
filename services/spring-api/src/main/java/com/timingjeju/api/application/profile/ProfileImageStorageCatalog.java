package com.timingjeju.api.application.profile;

public interface ProfileImageStorageCatalog {

  ProfileImageObjectPage list(ProfileImageScanCursor cursor, int limit);
}
