package com.timingjeju.api.application.profile;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProfileImageCommand {

  private static final Pattern ETAG = Pattern.compile("^\\\"profile-image-(0|[1-9][0-9]*)\\\"$");

  private final String objectKey;
  private final long expectedVersion;

  private ProfileImageCommand(String objectKey, long expectedVersion) {
    this.objectKey = objectKey;
    this.expectedVersion = expectedVersion;
  }

  public static ProfileImageCommand create(UUID ownerId, String objectKey, String ifMatch) {
    Objects.requireNonNull(ownerId, "ownerId must not be null");
    Matcher matcher = ETAG.matcher(ifMatch == null ? "" : ifMatch);
    if (!matcher.matches()) {
      throw ProfileImageException.invalidRequest();
    }
    long version;
    try {
      version = Long.parseLong(matcher.group(1));
    } catch (NumberFormatException failure) {
      throw ProfileImageException.invalidRequest();
    }
    if (objectKey != null) {
      String prefix = ownerId + "/profile/";
      if (!objectKey.startsWith(prefix) || !isCanonicalUuid(objectKey.substring(prefix.length()))) {
        throw ProfileImageException.invalidRequest();
      }
    }
    return new ProfileImageCommand(objectKey, version);
  }

  public Optional<String> objectKey() {
    return Optional.ofNullable(objectKey);
  }

  public long expectedVersion() {
    return expectedVersion;
  }

  private static boolean isCanonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }
}
