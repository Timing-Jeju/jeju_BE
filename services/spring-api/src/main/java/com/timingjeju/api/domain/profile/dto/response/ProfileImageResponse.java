package com.timingjeju.api.domain.profile.dto.response;

import com.timingjeju.api.application.profile.ProfileImageSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ProfileImageResponse(
    @Schema(nullable = true, format = "profile-image-object-key") String profileImageObjectKey,
    @Schema(nullable = true, format = "uri") String profileImageUrl,
    @Schema(allowableValues = {"provider", "storage", "none"}) String profileImageSource,
    @Schema(minimum = "0") long profileImageVersion,
    Instant updatedAt) {

  public static ProfileImageResponse from(ProfileImageSnapshot snapshot) {
    return new ProfileImageResponse(
        snapshot.objectKey(),
        snapshot.imageUrl(),
        snapshot.source().wireValue(),
        snapshot.version(),
        snapshot.updatedAt());
  }
}
