package com.timingjeju.api.application.profile;

public enum ProfileImageSource {
  PROVIDER("provider"),
  STORAGE("storage"),
  NONE("none");

  private final String wireValue;

  ProfileImageSource(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static ProfileImageSource fromDatabase(String value) {
    for (ProfileImageSource source : values()) {
      if (source.wireValue.equals(value)) {
        return source;
      }
    }
    throw ProfileImageException.storageUnavailable();
  }
}
