package com.timingjeju.api.application.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfileImageCommandTest {

  private static final UUID OWNER = UUID.fromString("09000000-0000-4000-8000-000000000001");
  private static final String GENERATION = "018f47a1-43d2-7b6e-9fa2-11a1cc32c675";

  @Test
  void owner의_lowercase_immutable_generation과_strong_ETag만_허용한다() {
    ProfileImageCommand command =
        ProfileImageCommand.create(OWNER, OWNER + "/profile/" + GENERATION, "\"profile-image-0\"");

    assertThat(command.objectKey()).contains(OWNER + "/profile/" + GENERATION);
    assertThat(command.expectedVersion()).isZero();
  }

  @Test
  void null_object_key는_explicit_clear로_해석한다() {
    ProfileImageCommand command =
        ProfileImageCommand.create(OWNER, null, "\"profile-image-9223372036854775807\"");

    assertThat(command.objectKey()).isEmpty();
    assertThat(command.expectedVersion()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void 다른_owner_extension_uppercase_UUID와_noncanonical_ETag를_거부한다() {
    for (String key :
        new String[] {
          "19000000-0000-4000-8000-000000000001/profile/" + GENERATION,
          OWNER + "/profile/" + GENERATION + ".webp",
          OWNER + "/profile/" + GENERATION.toUpperCase(),
          " " + OWNER + "/profile/" + GENERATION
        }) {
      assertThatThrownBy(() -> ProfileImageCommand.create(OWNER, key, "\"profile-image-0\""))
          .isInstanceOf(ProfileImageException.class)
          .extracting(failure -> ((ProfileImageException) failure).code())
          .isEqualTo("INVALID_PROFILE_IMAGE_REQUEST");
    }

    for (String etag :
        new String[] {
          null,
          "profile-image-0",
          "W/\"profile-image-0\"",
          "\"profile-image-01\"",
          "\"profile-image-9223372036854775808\""
        }) {
      assertThatThrownBy(() -> ProfileImageCommand.create(OWNER, null, etag))
          .isInstanceOf(ProfileImageException.class)
          .extracting(failure -> ((ProfileImageException) failure).code())
          .isEqualTo("INVALID_PROFILE_IMAGE_REQUEST");
    }
  }
}
