package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.timingjeju.api.application.profile.CurrentUserProfile;
import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.CurrentUserProfileStore;
import com.timingjeju.api.application.profile.ProfilePatchCommand;
import com.timingjeju.api.application.profile.service.CurrentUserProfileService;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcCurrentUserProfileStoreIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {

  private static final UUID USER_ID = UUID.fromString("18000000-0000-0000-0000-000000000101");

  @Autowired private CurrentUserProfileService service;
  @Autowired private CurrentUserProfileStore store;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @BeforeEach
  @AfterEach
  void cleanFixtures() {
    jdbc.execute("drop trigger if exists issue_18_reject_profile_patch on public.user_profiles");
    jdbc.execute("drop function if exists public.issue_18_reject_profile_patch() cascade");
    jdbc.update("delete from public.user_profiles where id = ?", USER_ID);
    jdbc.update("delete from auth.identities where user_id = ?", USER_ID);
    jdbc.update("delete from auth.users where id = ?", USER_ID);
  }

  @Test
  void GET은_64_provisioning후_profile과_active_provider를_canonical_projection으로_읽는다() {
    insertGoogleUser();

    CurrentUserProfile profile = service.read(currentUser());

    assertThat(profile.userId()).isEqualTo(USER_ID);
    assertThat(profile.email()).isEqualTo("canonical@issue18.test");
    assertThat(profile.nickname()).isEqualTo("기존 닉네임");
    assertThat(profile.profileImageUrl()).isEqualTo("https://images.example.invalid/original.png");
    assertThat(profile.locale()).isEqualTo("ko-KR");
    assertThat(profile.providers()).containsExactly("google");
    assertThat(profile.onboardingCompleted()).isFalse();
    assertThat(count("public.user_profiles", "id")).isOne();
    assertThat(count("public.social_accounts", "user_id")).isOne();
  }

  @Test
  void PATCH_nickname은_같은_transaction에서_update후_read하고_omitted_locale과_readonly값을_보존한다() {
    insertGoogleUser();
    CurrentUserProfile before = service.read(currentUser());

    CurrentUserProfile updated =
        service.update(currentUser(), new ProfilePatchCommand(true, "  새 닉네임  ", false, null));

    assertThat(updated.nickname()).isEqualTo("새 닉네임");
    assertThat(updated.locale()).isEqualTo(before.locale());
    assertThat(updated.email()).isEqualTo(before.email());
    assertThat(updated.profileImageUrl()).isEqualTo(before.profileImageUrl());
    assertThat(updated.providers()).isEqualTo(before.providers());
    assertThat(updated.updatedAt()).isAfterOrEqualTo(before.updatedAt());
    assertThat(store.read(USER_ID)).contains(updated);
    assertThat(count("public.user_profiles", "id")).isOne();
    assertThat(count("public.social_accounts", "user_id")).isOne();
  }

  @Test
  void PATCH_locale은_omitted_nickname과_email_image_provider를_보존한다() {
    insertGoogleUser();
    CurrentUserProfile before = service.read(currentUser());

    CurrentUserProfile updated =
        service.update(currentUser(), new ProfilePatchCommand(false, null, true, "ko-KR"));

    assertThat(updated.nickname()).isEqualTo(before.nickname());
    assertThat(updated.email()).isEqualTo(before.email());
    assertThat(updated.profileImageUrl()).isEqualTo(before.profileImageUrl());
    assertThat(updated.providers()).isEqualTo(before.providers());
  }

  @Test
  void missing과_deleted_profile은_조회되지_않고_update는_cause_free_503이다() {
    assertThat(store.read(USER_ID)).isEmpty();
    assertDataUnavailable(
        () ->
            store.update(USER_ID, new ProfilePatchCommand(true, "없음", false, null), Instant.now()));

    insertGoogleUser();
    service.read(currentUser());
    jdbc.update("update public.user_profiles set status = 'deleted' where id = ?", USER_ID);

    assertThat(store.read(USER_ID)).isEmpty();
    assertDataUnavailable(() -> service.read(currentUser()));
    assertThat(count("public.user_profiles", "id")).isOne();
  }

  @Test
  void 성공한_UPDATE뒤_read_failure는_전체를_rollback하고_독립_connection에서도_cause_free_503이다()
      throws SQLException {
    insertGoogleUser();
    service.read(currentUser());
    jdbc.update(
        """
        update public.user_profiles
        set locale = 'en-US', updated_at = '2026-08-25T01:00:00Z'
        where id = ?
        """,
        USER_ID);
    ProfileState before = independentProfileState();
    installPostUpdateReadFailureTrigger();

    assertDataUnavailable(
        () ->
            store.update(
                USER_ID,
                new ProfilePatchCommand(true, "read 단계 실패", true, "ko-KR"),
                Instant.parse("2026-08-25T02:00:00Z")));

    assertThat(independentProfileState()).isEqualTo(before);
    assertThat(store.read(USER_ID)).isPresent();
  }

  private void insertGoogleUser() {
    jdbc.update(
        "insert into auth.users (id, email, raw_user_meta_data) values (?, ?, '{}'::jsonb)",
        USER_ID,
        "auth-owner@issue18.test");
    jdbc.update(
        """
        insert into auth.identities (id, user_id, provider, provider_id, identity_data)
        values (?, ?, 'google', ?, ?::jsonb)
        """,
        "google-issue18",
        USER_ID,
        "google-subject-issue18",
        """
        {"email":"canonical@issue18.test","nickname":"기존 닉네임",
         "picture":"https://images.example.invalid/original.png"}
        """);
  }

  private void installPostUpdateReadFailureTrigger() {
    jdbc.execute(
        """
        create function public.issue_18_reject_profile_patch()
        returns trigger
        language plpgsql
        set search_path = ''
        as $$
        begin
          if new.nickname = 'read 단계 실패' then
            new.status := 'deleted';
          end if;
          return new;
        end;
        $$
        """);
    jdbc.execute(
        """
        create trigger issue_18_reject_profile_patch
        before update on public.user_profiles
        for each row execute function public.issue_18_reject_profile_patch()
        """);
  }

  private int count(String table, String userColumn) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + userColumn + " = ?", Integer.class, USER_ID);
  }

  private ProfileState independentProfileState() throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                """
                select email, nickname, profile_image_url, locale, status, updated_at
                from public.user_profiles where id = ?
                """)) {
      statement.setObject(1, USER_ID);
      try (var result = statement.executeQuery()) {
        if (!result.next()) {
          throw new AssertionError("독립 connection에서 profile row를 찾을 수 없습니다.");
        }
        ProfileState state =
            new ProfileState(
                result.getString("email"),
                result.getString("nickname"),
                result.getString("profile_image_url"),
                result.getString("locale"),
                result.getString("status"),
                result.getTimestamp("updated_at").toInstant());
        if (result.next()) {
          throw new AssertionError("독립 connection에서 profile row가 중복됐습니다.");
        }
        return state;
      }
    }
  }

  private static CurrentUser currentUser() {
    return new CurrentUser(USER_ID, AuthenticatedRole.AUTHENTICATED, null);
  }

  private static void assertDataUnavailable(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    CurrentUserProfileException failure =
        catchThrowableOfType(CurrentUserProfileException.class, call);
    assertThat(failure.code()).isEqualTo("PROFILE_DATA_UNAVAILABLE");
    assertThat(failure).hasNoCause().hasMessage(null);
  }

  private record ProfileState(
      String email,
      String nickname,
      String profileImageUrl,
      String locale,
      String status,
      Instant updatedAt) {}
}
