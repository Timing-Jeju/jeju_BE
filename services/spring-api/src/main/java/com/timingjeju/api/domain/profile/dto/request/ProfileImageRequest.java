package com.timingjeju.api.domain.profile.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.profile.ProfileImageCommand;
import com.timingjeju.api.application.profile.ProfileImageException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
    requiredProperties = "profileImageObjectKey")
public final class ProfileImageRequest {

  private static final String OBJECT_KEY_PATTERN =
      "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/profile/"
          + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

  private String profileImageObjectKey;
  private boolean present;

  @Schema(nullable = true, format = "profile-image-object-key", pattern = OBJECT_KEY_PATTERN)
  public String getProfileImageObjectKey() {
    return profileImageObjectKey;
  }

  @JsonSetter("profileImageObjectKey")
  public void setProfileImageObjectKey(Object value) {
    if (value != null && !(value instanceof String)) {
      throw ProfileImageException.invalidRequest();
    }
    profileImageObjectKey = (String) value;
    present = true;
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw ProfileImageException.invalidRequest();
  }

  public ProfileImageCommand toCommand(UUID ownerId, String ifMatch) {
    if (!present) {
      throw ProfileImageException.invalidRequest();
    }
    return ProfileImageCommand.create(ownerId, profileImageObjectKey, ifMatch);
  }
}
