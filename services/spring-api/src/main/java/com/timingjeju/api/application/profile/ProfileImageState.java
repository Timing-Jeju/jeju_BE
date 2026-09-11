package com.timingjeju.api.application.profile;

import java.time.Instant;
import java.util.Objects;

public record ProfileImageState(
    String objectKey,
    String storageEtag,
    String providerImageUrl,
    ProfileImageSource source,
    long version,
    Instant updatedAt) {

  public ProfileImageState {
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    if (version < 0) {
      throw ProfileImageException.storageUnavailable();
    }
    boolean bothStorageFields = objectKey != null && storageEtag != null;
    boolean neitherStorageField = objectKey == null && storageEtag == null;
    if ((source == ProfileImageSource.STORAGE && !bothStorageFields)
        || (source != ProfileImageSource.STORAGE && !neitherStorageField)) {
      throw ProfileImageException.storageUnavailable();
    }
  }
}
