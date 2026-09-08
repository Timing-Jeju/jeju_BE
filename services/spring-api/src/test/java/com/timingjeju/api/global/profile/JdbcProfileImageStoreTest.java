package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JdbcProfileImageStoreTest {

  private static final UUID OWNER = UUID.fromString("09000000-0000-4000-8000-000000000001");
  private static final String KEY = OWNER + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675";

  @Test
  void companion_GET과_clear는_active_provider를_우선하고_legacy_url을_compatibility로_조회한다() {
    String sql = JdbcProfileImageStore.contractSql().toLowerCase();

    assertThat(sql)
        .contains(
            "from public.social_accounts",
            "revoked_at is null",
            "provider_profile_image_url",
            "profile_image_url",
            "case provider when 'google' then 1 when 'kakao' then 2 when 'naver' then 3")
        .doesNotContain("profile_image_source = case when profile_image_url");
  }

  @Test
  void row_lock안의_independent_HEAD_ETag가_바뀌면_profile과_outbox_write전에_conflict다() {
    ProfileImageMetadata before = metadata("\"etag-1\"");
    ProfileImageMetadata after = metadata("\"etag-2\"");

    assertThatThrownBy(() -> JdbcProfileImageStore.requireSameGeneration(before, after))
        .isInstanceOf(ProfileImageException.class)
        .extracting(failure -> ((ProfileImageException) failure).code())
        .isEqualTo("PROFILE_IMAGE_VERSION_CONFLICT");
  }

  private static ProfileImageMetadata metadata(String etag) {
    return new ProfileImageMetadata(
        KEY, OWNER, "image/jpeg", 1, etag, Instant.parse("2026-09-02T00:00:00Z"));
  }
}
