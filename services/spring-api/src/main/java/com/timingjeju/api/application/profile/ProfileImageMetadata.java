package com.timingjeju.api.application.profile;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record ProfileImageMetadata(
    String objectKey,
    UUID ownerId,
    String contentType,
    long sizeBytes,
    String storageEtag,
    Instant updatedAt) {

  private static final long MAXIMUM_BYTES = 5L * 1024 * 1024;
  private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
  private static final Pattern STRONG_ETAG = Pattern.compile("^\\\"[^\\\"\\p{Cntrl}]+\\\"$");

  public ProfileImageMetadata {
    Objects.requireNonNull(objectKey, "objectKey must not be null");
    Objects.requireNonNull(ownerId, "ownerId must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  public boolean isCleanupSafe() {
    return sizeBytes >= 1
        && sizeBytes <= MAXIMUM_BYTES
        && MEDIA_TYPES.contains(contentType)
        && storageEtag != null
        && STRONG_ETAG.matcher(storageEtag).matches();
  }
}
