package com.timingjeju.api.application.profile;

public final class ProfileImageException extends RuntimeException {

  private final String code;

  private ProfileImageException(String code) {
    super(null, null, false, false);
    this.code = code;
  }

  public static ProfileImageException invalidRequest() {
    return new ProfileImageException("INVALID_PROFILE_IMAGE_REQUEST");
  }

  public static ProfileImageException notFound() {
    return new ProfileImageException("PROFILE_IMAGE_NOT_FOUND");
  }

  public static ProfileImageException versionConflict() {
    return new ProfileImageException("PROFILE_IMAGE_VERSION_CONFLICT");
  }

  public static ProfileImageException tooLarge() {
    return new ProfileImageException("PROFILE_IMAGE_TOO_LARGE");
  }

  public static ProfileImageException mediaTypeUnsupported() {
    return new ProfileImageException("PROFILE_IMAGE_MEDIA_TYPE_UNSUPPORTED");
  }

  public static ProfileImageException storageUnavailable() {
    return new ProfileImageException("PROFILE_IMAGE_STORAGE_UNAVAILABLE");
  }

  public String code() {
    return code;
  }
}
