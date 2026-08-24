package com.timingjeju.api.domain.profile.dto.response;

import com.timingjeju.api.application.profile.CurrentUserProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CurrentUserProfileResponse(
    @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID userId,
    @Schema(accessMode = Schema.AccessMode.READ_ONLY, nullable = true) String email,
    @Schema(nullable = true) String nickname,
    @Schema(accessMode = Schema.AccessMode.READ_ONLY, nullable = true) String profileImageUrl,
    String locale,
    @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<String> providers,
    @Schema(accessMode = Schema.AccessMode.READ_ONLY) boolean onboardingCompleted,
    @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant updatedAt) {

  public static CurrentUserProfileResponse from(CurrentUserProfile profile) {
    return new CurrentUserProfileResponse(
        profile.userId(),
        profile.email(),
        profile.nickname(),
        profile.profileImageUrl(),
        profile.locale(),
        profile.providers(),
        profile.onboardingCompleted(),
        profile.updatedAt());
  }
}
