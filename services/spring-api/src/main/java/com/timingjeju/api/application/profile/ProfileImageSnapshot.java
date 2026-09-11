package com.timingjeju.api.application.profile;

import java.time.Instant;
import java.util.Optional;

public record ProfileImageSnapshot(
    String objectKey, String imageUrl, ProfileImageSource source, long version, Instant updatedAt) {

  public Optional<String> profileImageObjectKey() {
    return Optional.ofNullable(objectKey);
  }

  public Optional<String> profileImageUrl() {
    return Optional.ofNullable(imageUrl);
  }

  public ProfileImageSource profileImageSource() {
    return source;
  }

  public long profileImageVersion() {
    return version;
  }

  public String etag() {
    return "\"profile-image-" + version + "\"";
  }
}
