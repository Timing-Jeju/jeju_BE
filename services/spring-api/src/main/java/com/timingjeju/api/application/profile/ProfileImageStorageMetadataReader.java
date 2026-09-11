package com.timingjeju.api.application.profile;

import java.util.Optional;

@FunctionalInterface
public interface ProfileImageStorageMetadataReader {
  Optional<ProfileImageMetadata> read(String objectKey);
}
