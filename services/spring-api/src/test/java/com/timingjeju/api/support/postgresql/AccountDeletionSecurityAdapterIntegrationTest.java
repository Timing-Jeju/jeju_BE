package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.accountdeletion.adapter.JdbcRecentAuthSessionGateway;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionRecord;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionStatus;
import com.timingjeju.api.domain.accountdeletion.repository.JdbcAccountDeletionRepository;
import com.timingjeju.api.global.security.JdbcAccountDeletionPendingAccess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class AccountDeletionSecurityAdapterIntegrationTest {
  private static final String TARGET = "20260919030000_account_deletion_security_correction.sql";
  private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void actual_adapter는_UTC시간_profile_null_deny와_경계_cleanup을_보존한다(String image) throws Exception {
    try (var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image)) {
      container.start();
      PostgreSqlTestContainerFactory.executeScript(
          container, Path.of("../../supabase/migrations", TARGET));
      var dataSource =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(dataSource);
      var named = new NamedParameterJdbcTemplate(dataSource);
      var repository = new JdbcAccountDeletionRepository(named);
      UUID user = UUID.fromString("61000000-0000-0000-0000-000000000061");
      UUID session = UUID.fromString("61000000-0000-0000-0000-000000000062");
      jdbc.update("insert into auth.users(id,email) values (?,?)", user, "issue61@example.test");
      jdbc.update(
          "insert into public.user_profiles(id,email) values (?,?)", user, "issue61@example.test");
      jdbc.update(
          "insert into auth.sessions(id,user_id,created_at) values (?,?,?)",
          session,
          user,
          OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));

      assertThat(
              new JdbcRecentAuthSessionGateway(named)
                  .isRecent(user, session, NOW.minus(Duration.ofMinutes(15))))
          .isTrue();

      byte[] fingerprint =
          MessageDigest.getInstance("SHA-256")
              .digest(user.toString().getBytes(StandardCharsets.US_ASCII));
      repository.insert(record(user, fingerprint));
      assertThat(repository.findForReplay(user, new byte[32])).isPresent();
      var deny = new JdbcAccountDeletionPendingAccess(named);
      assertThat(deny.isPending(user)).isTrue();

      jdbc.update("update public.account_deletion_requests set user_profile_id=null");
      jdbc.update("delete from auth.users where id=?", user);
      assertThat(deny.isPending(user)).isTrue();
      for (String status : java.util.List.of("failed", "succeeded")) {
        jdbc.update(
            "update public.account_deletion_requests set status=?, completed_at=?",
            status,
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(deny.isPending(user)).isTrue();
      }
      jdbc.update(
          "update public.account_deletion_requests set completed_at=?",
          OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(24)), ZoneOffset.UTC));

      assertThat(repository.clearExpiredSecrets(NOW.minusSeconds(1), Duration.ofHours(24), 10))
          .isZero();
      assertThat(repository.clearExpiredSecrets(NOW, Duration.ofHours(24), 10)).isOne();
      assertThat(repository.clearExpiredSecrets(NOW, Duration.ofHours(24), 10)).isZero();
      assertThat(
              jdbc.queryForObject(
                  "select status_token_ciphertext is null and status_token_key_version is null from public.account_deletion_requests",
                  Boolean.class))
          .isTrue();

      jdbc.update("update public.account_deletion_requests set status='cancelled'");
      assertThat(deny.isPending(user)).isFalse();
    }
  }

  private static AccountDeletionRecord record(UUID user, byte[] fingerprint) {
    return new AccountDeletionRecord(
        "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        user,
        fingerprint,
        new byte[32],
        new byte[] {
          1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
          0, 0
        },
        new byte[] {
          2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
          0, 0
        },
        "ciphertext",
        "v1",
        NOW.plus(Duration.ofHours(48)),
        "subject-ciphertext",
        "v1",
        AccountDeletionStatus.QUEUED,
        "queued",
        null,
        NOW.minus(Duration.ofHours(1)),
        null);
  }
}
