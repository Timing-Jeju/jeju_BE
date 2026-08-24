package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JdbcCurrentUserProfileStoreContractTest {

  @Test
  void SQL은_canonical_user_id로만_profile과_active_provider를_조회한다() {
    String sql = JdbcCurrentUserProfileStore.contractSql().toLowerCase();

    assertThat(sql)
        .contains("from public.user_profiles", "where id = ?", "from public.social_accounts")
        .contains("where user_id = ? and revoked_at is null")
        .doesNotContain("auth.users", "user_metadata", "provider_token");
  }

  @Test
  void SQL은_nickname_locale만_수정하고_read_only필드를_갱신하지_않는다() {
    String update =
        JdbcCurrentUserProfileStore.contractSql()
            .toLowerCase()
            .substring(
                JdbcCurrentUserProfileStore.contractSql()
                    .toLowerCase()
                    .indexOf("update public.user_profiles"));

    assertThat(update)
        .contains("set nickname =", "locale =", "updated_at =", "where id = ?")
        .doesNotContain("email =", "profile_image_url =", "provider =", "user_id =");
  }
}
