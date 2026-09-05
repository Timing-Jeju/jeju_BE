package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.profile.ProfileImageCleanupJob;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JdbcProfileImageCleanupStoreTest {

  @Test
  void claim은_due_or_expired_lease를_SKIP_LOCKED하고_account_deletion을_절대_claim하지_않는다() {
    String sql = JdbcProfileImageCleanupStore.contractSql().toLowerCase();

    assertThat(sql)
        .contains(
            "for update skip locked",
            "status in ('pending', 'retry')",
            "status = 'claimed'",
            "claimed_at <= ?",
            "reason in ('replacement', 'clear', 'orphan')",
            "attempt_count = attempt_count + 1")
        .doesNotContain("reason in ('replacement', 'clear', 'orphan', 'account_deletion')");
  }

  @Test
  void terminal_retry_mutation은_claim_token과_exact_generation으로_fence된다() {
    String sql = JdbcProfileImageCleanupStore.contractSql().toLowerCase();

    assertThat(sql)
        .contains(
            "status = 'succeeded'",
            "status = 'retry'",
            "claim_token = ?",
            "object_key = ?",
            "storage_etag = ?")
        .doesNotContain(" state ", "state =");
  }

  @Test
  void delete_callback은_profile_row_lock후_current_key_ETag가_아닐때만_실행된다() {
    String sql = JdbcProfileImageCleanupStore.contractSql().toLowerCase();

    assertThat(sql)
        .contains(
            "from public.user_profiles",
            "for update",
            "profile_image_object_key",
            "profile_image_storage_etag");
  }

  @Test
  void orphan_enqueue는_owner뿐아니라_object_key를_참조하는_모든_profile을_lock한다() {
    String sql = JdbcProfileImageCleanupStore.contractSql().toLowerCase();

    assertThat(sql)
        .contains(
            "where status <> 'deleted'",
            "id = ? or profile_image_object_key = ?",
            "order by id",
            "for update");
  }

  @Test
  void current_profile이_same_key를_참조하면_ETag가달라도_delete를_차단한다() {
    UUID owner = UUID.fromString("09000000-0000-4000-8000-000000000001");
    String key = owner + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675";
    ProfileImageCleanupJob job =
        new ProfileImageCleanupJob(
            UUID.fromString("09000000-0000-4000-8000-000000000078"),
            UUID.fromString("09000000-0000-4000-8000-000000000079"),
            owner,
            key,
            "\"old-etag\"",
            1,
            "replacement",
            1);

    assertThat(JdbcProfileImageCleanupStore.isCurrentReference(key, job)).isTrue();
    assertThat(JdbcProfileImageCleanupStore.isCurrentReference(null, job)).isFalse();
  }
}
