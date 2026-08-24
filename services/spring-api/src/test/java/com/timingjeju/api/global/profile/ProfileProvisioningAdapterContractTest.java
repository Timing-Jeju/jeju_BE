package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.annotation.Transactional;

@Tag("unit")
class ProfileProvisioningAdapterContractTest {

  @Test
  void auth_identity_reader는_auth_identity의_allowlist만_SELECT한다() {
    String sql = JdbcAuthIdentityReader.SELECT_IDENTITIES_SQL.toLowerCase();

    assertThat(sql.stripLeading()).startsWith("select");
    assertThat(sql)
        .contains("from auth.identities", "where user_id = ?", "provider", "provider_id")
        .contains(
            "identity_data ->> 'email'",
            "identity_data ->> 'nickname'",
            "identity_data ->> 'picture'")
        .doesNotContain(
            "insert ",
            "update ",
            "delete ",
            "raw_user_meta_data",
            "raw_app_meta_data",
            "select identity_data",
            "identity_data::text");
  }

  @Test
  void profile_store는_profile과_social을_한_transaction으로_처리한다() throws Exception {
    Method provision =
        JdbcProfileProvisioningStore.class.getMethod(
            "provision", com.timingjeju.api.application.profile.ProfileProvisioningRequest.class);

    assertThat(provision.isAnnotationPresent(Transactional.class)).isTrue();
  }

  @Test
  void profile_store_SQL은_auth_schema를_수정하지_않고_raw_profile을_빈_object로_고정한다() {
    String sql = JdbcProfileProvisioningStore.contractSql().toLowerCase();

    assertThat(sql)
        .contains("insert into public.user_profiles", "insert into public.social_accounts")
        .contains("'{}'::jsonb")
        .contains("on conflict (id)", "on conflict (user_id, provider)")
        .doesNotContain(
            "insert into auth.",
            "update auth.",
            "delete from auth.",
            "raw_user_meta_data",
            "raw_app_meta_data");
  }

  @Test
  void profile_store는_email_없는_id_claim으로_동일_user를_먼저_직렬화한다() {
    String sql = JdbcProfileProvisioningStore.contractSql().toLowerCase();
    String claim =
        """
        insert into public.user_profiles (
          id, created_at, updated_at, last_login_at
        ) values (?, ?, ?, ?)
        on conflict (id) do nothing
        """
            .strip();
    String emailConflict = "select 1 from public.user_profiles where email = ? and id <> ?";
    String profileUpsert =
        """
        insert into public.user_profiles (
          id, email, nickname, profile_image_url, created_at, updated_at, last_login_at
        )
        """
            .strip();

    assertThat(sql).contains(claim, emailConflict, profileUpsert);
    assertThat(sql.indexOf(claim)).isLessThan(sql.indexOf(emailConflict));
    assertThat(sql.indexOf(claim)).isLessThan(sql.indexOf(profileUpsert));
  }

  @Test
  void PostgreSQL_constraint_metadata만_known_충돌로_분류하고_raw_detail을_버린다() {
    assertStableFailure(
        failure("user_profiles_email_key"),
        com.timingjeju.api.application.profile.ProfileProvisioningError.EMAIL_OWNERSHIP_CONFLICT);
    assertStableFailure(
        failure("social_accounts_provider_provider_user_id_key"),
        com.timingjeju.api.application.profile.ProfileProvisioningError.PROVIDER_SUBJECT_CONFLICT);
    assertStableFailure(
        failure("social_accounts_user_id_provider_key"),
        com.timingjeju.api.application.profile.ProfileProvisioningError.PROVIDER_SUBJECT_CONFLICT);
  }

  @Test
  void unknown_constraint와_storage_failure는_raw_SQL이나_ID없이_causeFree_오류다() {
    assertStableFailure(
        failure("unknown_constraint"),
        com.timingjeju.api.application.profile.ProfileProvisioningError.STORAGE_UNAVAILABLE);
    assertStableFailure(
        new DataAccessResourceFailureException(
            "select * from private where user_id=64000000-0000-0000-0000-000000000001 provider=google"),
        com.timingjeju.api.application.profile.ProfileProvisioningError.STORAGE_UNAVAILABLE);
  }

  @Test
  void programmer_boundary인_null_dependency는_storage_오류로_숨기지_않는다() {
    org.assertj.core.api.Assertions.assertThatNullPointerException()
        .isThrownBy(() -> new JdbcProfileProvisioningStore(null));
  }

  @Test
  void identity_SELECT_storage_failure도_raw_cause없는_안정적인_오류로_닫는다() {
    JdbcAuthIdentityReader reader =
        new JdbcAuthIdentityReader(
            new JdbcTemplate(
                new AbstractDataSource() {
                  @Override
                  public Connection getConnection() throws SQLException {
                    throw new SQLException(
                        "select identity_data for 64000000-0000-0000-0000-000000000001 provider=google");
                  }

                  @Override
                  public Connection getConnection(String username, String password)
                      throws SQLException {
                    return getConnection();
                  }
                }));

    org.assertj.core.api.ThrowableAssert.ThrowingCallable call =
        () -> reader.readByUserId(UUID.fromString("64000000-0000-0000-0000-000000000001"));
    com.timingjeju.api.application.profile.ProfileProvisioningException failure =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            com.timingjeju.api.application.profile.ProfileProvisioningException.class, call);

    assertThat(failure.code())
        .isEqualTo(
            com.timingjeju.api.application.profile.ProfileProvisioningError.STORAGE_UNAVAILABLE);
    assertThat(failure)
        .hasNoCause()
        .hasMessageNotContaining("select")
        .hasMessageNotContaining("64000000")
        .hasMessageNotContaining("provider=google");
  }

  private static DataIntegrityViolationException failure(String constraint) {
    String fields = "SERROR\0C23505\0Mraw SQL UUID provider detail\0n" + constraint + "\0\0";
    return new DataIntegrityViolationException(
        "raw outer SQL UUID provider detail", new PSQLException(new ServerErrorMessage(fields)));
  }

  private static void assertStableFailure(
      RuntimeException storageFailure,
      com.timingjeju.api.application.profile.ProfileProvisioningError expected) {
    RuntimeException translated =
        JdbcProfileProvisioningStore.translateForTest(
            (org.springframework.dao.DataAccessException) storageFailure);

    assertThat(translated)
        .isInstanceOf(com.timingjeju.api.application.profile.ProfileProvisioningException.class)
        .hasNoCause();
    assertThat(
            ((com.timingjeju.api.application.profile.ProfileProvisioningException) translated)
                .code())
        .isEqualTo(expected);
    assertThat(translated.getMessage())
        .doesNotContain("select", "uuid", "provider=", "64000000", "raw", "sql");
  }
}
