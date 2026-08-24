package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.profile.ProfileProvisioningError;
import com.timingjeju.api.application.profile.ProfileProvisioningException;
import com.timingjeju.api.application.profile.ProfileProvisioningRequest;
import com.timingjeju.api.application.profile.ProfileProvisioningStore;
import com.timingjeju.api.application.profile.ProvisionedCurrentUser;
import com.timingjeju.api.application.profile.ProvisioningSocialAccount;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcProfileProvisioningStoreIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {

  private static final UUID USER_A = UUID.fromString("64000000-0000-0000-0000-000000000101");
  private static final UUID USER_B = UUID.fromString("64000000-0000-0000-0000-000000000102");

  @Autowired private CurrentUserProvisioningService service;
  @Autowired private ProfileProvisioningStore store;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  @AfterEach
  void cleanFixtures() {
    jdbc.execute("drop trigger if exists issue_64_insert_barrier on public.user_profiles");
    jdbc.execute("drop function if exists public.issue_64_wait_for_competing_insert() cascade");
    jdbc.execute("drop sequence if exists public.issue_64_insert_barrier_sequence");
    jdbc.update("delete from public.user_profiles where id in (?, ?)", USER_A, USER_B);
    jdbc.update("delete from auth.identities where user_id in (?, ?)", USER_A, USER_B);
    jdbc.update("delete from auth.users where id in (?, ?)", USER_A, USER_B);
  }

  @Test
  void 실제_email_identity는_profile_하나만_lazy_provision한다() {
    insertAuthUser(USER_A, "canonical-a@issue64.test", "{}");
    insertIdentity(
        USER_A,
        "email-a",
        "email",
        "email-subject-a",
        """
        {"email":"email-a@issue64.test","nickname":"이메일 A","picture":"profiles/a.png"}
        """);

    service.provision(currentUser(USER_A));
    service.provision(currentUser(USER_A));

    assertThat(count("public.user_profiles", USER_A)).isOne();
    assertThat(count("public.social_accounts", USER_A)).isZero();
    assertThat(profile(USER_A))
        .containsEntry("email", "email-a@issue64.test")
        .containsEntry("nickname", "이메일 A")
        .containsEntry("profile_image_url", "profiles/a.png");
  }

  @Test
  void google_kakao_customNaver는_providerId로_각각_한_행에_수렴하고_raw는_비어있다() {
    insertAuthUser(USER_A, "oauth-a@issue64.test", "{}");
    insertIdentity(USER_A, "naver-a", "custom:naver", "naver-A", json("n"));
    insertIdentity(USER_A, "google-a", "google", "\u2003Google-Case-A\u2003", json("g"));
    insertIdentity(USER_A, "kakao-a", "kakao", "kakao-A", json("k"));

    assertThat(service.provision(currentUser(USER_A)).providers())
        .containsExactly("google", "kakao", "custom:naver");
    service.provision(currentUser(USER_A));

    assertThat(
            jdbc.queryForList(
                """
                select provider, provider_user_id, raw_profile::text as raw_profile
                from public.social_accounts where user_id = ? order by provider
                """,
                USER_A))
        .containsExactly(
            java.util.Map.of(
                "provider",
                "google",
                "provider_user_id",
                "\u2003Google-Case-A\u2003",
                "raw_profile",
                "{}"),
            java.util.Map.of(
                "provider", "kakao", "provider_user_id", "kakao-A", "raw_profile", "{}"),
            java.util.Map.of(
                "provider", "naver", "provider_user_id", "naver-A", "raw_profile", "{}"));
  }

  @Test
  void raw_user_metadata의_subject와_profile_위조는_무시한다() {
    insertAuthUser(
        USER_A,
        "canonical-a@issue64.test",
        """
        {"sub":"forged-sub","provider_id":"forged-provider","nickname":"위조","picture":"evil"}
        """);
    insertIdentity(
        USER_A,
        "google-a",
        "google",
        "real-provider-subject",
        """
        {"email":"real@issue64.test","nickname":"실제","picture":"profiles/real.png",
         "sub":"forged-identity-subject","raw_profile":{"token":"forbidden"}}
        """);

    service.provision(currentUser(USER_A));

    assertThat(profile(USER_A))
        .containsEntry("email", "real@issue64.test")
        .containsEntry("nickname", "실제")
        .containsEntry("profile_image_url", "profiles/real.png");
    assertThat(
            jdbc.queryForObject(
                "select provider_user_id from public.social_accounts where user_id = ?",
                String.class,
                USER_A))
        .isEqualTo("real-provider-subject");
  }

  @Test
  void 동일_email의_다른_sub는_자동_병합하지_않고_안정적인_충돌로_거부한다() {
    insertAuthUser(USER_A, "canonical-a@issue64.test", "{}");
    insertAuthUser(USER_B, "canonical-b@issue64.test", "{}");
    insertIdentity(USER_A, "email-a", "email", "email-a", jsonWithEmail("shared@issue64.test"));
    insertIdentity(USER_B, "email-b", "email", "email-b", jsonWithEmail("shared@issue64.test"));
    service.provision(currentUser(USER_A));

    ProfileProvisioningException failure =
        catchThrowableOfType(
            ProfileProvisioningException.class, () -> service.provision(currentUser(USER_B)));
    assertThat(failure.code()).isEqualTo(ProfileProvisioningError.EMAIL_OWNERSHIP_CONFLICT);
    assertThat(failure)
        .hasNoCause()
        .hasMessageNotContaining("shared@issue64.test")
        .hasMessageNotContaining(USER_A.toString())
        .hasMessageNotContaining(USER_B.toString());
    assertThat(count("public.user_profiles", USER_B)).isZero();
  }

  @Test
  void provider_subject_충돌이면_profile과_앞선_social_insert도_같이_rollback한다() {
    insertAuthUser(USER_A, "canonical-a@issue64.test", "{}");
    insertAuthUser(USER_B, "canonical-b@issue64.test", "{}");
    store.provision(
        request(
            USER_A, "canonical-a@issue64.test", List.of(social("kakao", "shared-kakao-subject"))));

    ProfileProvisioningException failure =
        catchThrowableOfType(
            ProfileProvisioningException.class,
            () ->
                store.provision(
                    request(
                        USER_B,
                        "canonical-b@issue64.test",
                        List.of(
                            social("google", "new-google-subject"),
                            social("kakao", "shared-kakao-subject")))));
    assertThat(failure.code()).isEqualTo(ProfileProvisioningError.PROVIDER_SUBJECT_CONFLICT);
    assertThat(failure)
        .hasNoCause()
        .hasMessageNotContaining("shared-kakao-subject")
        .hasMessageNotContaining(USER_A.toString())
        .hasMessageNotContaining(USER_B.toString());
    assertThat(count("public.user_profiles", USER_B)).isZero();
    assertThat(count("public.social_accounts", USER_B)).isZero();
  }

  @Test
  void 동일_sub의_동시_최초_GET은_profile과_social_각_한_행으로_수렴한다() throws Exception {
    insertAuthUser(USER_A, "canonical-a@issue64.test", "{}");
    insertIdentity(USER_A, "google-a", "google", "google-concurrent", json("parallel"));
    installConcurrentInsertBarrier();
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<ProvisionedCurrentUser> first = executor.submit(() -> provisionAfter(start, USER_A));
      Future<ProvisionedCurrentUser> second = executor.submit(() -> provisionAfter(start, USER_A));
      start.countDown();
      ProvisionedCurrentUser firstResult = first.get(10, TimeUnit.SECONDS);
      ProvisionedCurrentUser secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(firstResult).isEqualTo(secondResult);
    }

    assertThat(count("public.user_profiles", USER_A)).isOne();
    assertThat(count("public.social_accounts", USER_A)).isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from public.user_profiles
                where id = ?
                  and email is not null
                  and nickname is not null
                  and profile_image_url is not null
                  and last_login_at is not null
                """,
                Integer.class,
                USER_A))
        .isOne();
  }

  private ProvisionedCurrentUser provisionAfter(CountDownLatch start, UUID userId) {
    try {
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("동시 시작 latch timeout");
      }
      return service.provision(currentUser(userId));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("동시 provisioning이 중단되었습니다.", interrupted);
    }
  }

  private void installConcurrentInsertBarrier() {
    jdbc.execute("create sequence public.issue_64_insert_barrier_sequence");
    jdbc.execute(
        """
        create function public.issue_64_wait_for_competing_insert()
        returns trigger
        language plpgsql
        set search_path = ''
        as $$
        declare
          deadline timestamptz := clock_timestamp() + interval '5 seconds';
        begin
          if new.id <> '64000000-0000-0000-0000-000000000101'::uuid then
            return new;
          end if;
          perform nextval('public.issue_64_insert_barrier_sequence');
          loop
            exit when (select last_value from public.issue_64_insert_barrier_sequence) >= 2;
            if clock_timestamp() >= deadline then
              raise exception using errcode = '57014', message = 'issue 64 insert barrier timeout';
            end if;
            perform pg_sleep(0.01);
          end loop;
          return new;
        end;
        $$
        """);
    jdbc.execute(
        """
        create trigger issue_64_insert_barrier
        before insert on public.user_profiles
        for each row execute function public.issue_64_wait_for_competing_insert()
        """);
  }

  private void insertAuthUser(UUID userId, String email, String rawUserMetadata) {
    jdbc.update(
        """
        insert into auth.users (id, email, raw_user_meta_data) values (?, ?, ?::jsonb)
        """,
        userId,
        email,
        rawUserMetadata);
  }

  private void insertIdentity(
      UUID userId, String identityId, String provider, String providerId, String identityData) {
    jdbc.update(
        """
        insert into auth.identities (id, user_id, provider, provider_id, identity_data)
        values (?, ?, ?, ?, ?::jsonb)
        """,
        identityId,
        userId,
        provider,
        providerId,
        identityData);
  }

  private int count(String table, UUID userId) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where " + profileUserColumn(table) + " = ?",
        Integer.class,
        userId);
  }

  private static String profileUserColumn(String table) {
    return table.endsWith("user_profiles") ? "id" : "user_id";
  }

  private java.util.Map<String, Object> profile(UUID userId) {
    return jdbc.queryForMap(
        "select email, nickname, profile_image_url from public.user_profiles where id = ?", userId);
  }

  private static CurrentUser currentUser(UUID userId) {
    return new CurrentUser(userId, AuthenticatedRole.AUTHENTICATED, null);
  }

  private static ProfileProvisioningRequest request(
      UUID userId, String email, List<ProvisioningSocialAccount> accounts) {
    return new ProfileProvisioningRequest(
        userId,
        email,
        "Issue 64",
        "profiles/issue64.png",
        accounts,
        Instant.parse("2026-08-24T12:00:00Z"));
  }

  private static ProvisioningSocialAccount social(String provider, String providerId) {
    return new ProvisioningSocialAccount(
        provider, providerId, provider + "@issue64.test", provider, provider + ".png");
  }

  private static String json(String prefix) {
    return """
        {"email":"%s@issue64.test","nickname":"%s","picture":"profiles/%s.png"}
        """
        .formatted(prefix, prefix, prefix);
  }

  private static String jsonWithEmail(String email) {
    return """
        {"email":"%s"}
        """
        .formatted(email);
  }
}
