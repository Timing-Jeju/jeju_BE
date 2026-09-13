package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionLease;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionStep;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.JdbcAccountDeletionWorkRepository;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.JdbcAppOwnedDataErasure;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class AccountDeletionWorkerPostgreSqlIntegrationTest {
  private static final List<String> IMAGES =
      List.of("postgis/postgis:16-3.4", "postgis/postgis:17-3.5");
  private static final Instant NOW = Instant.parse("2026-09-14T03:00:00Z");

  @Test
  void PostgreSQL16과17에서_reclaim은_stale_complete_retry_authClear를_모두_rollback한다() {
    forEachDatabase(
        database -> {
          database.jdbc.execute("set time zone 'Asia/Seoul'");
          String id = "01K4V106000000000000000101";
          UUID user = UUID.fromString("46d9a0ca-3472-4f7e-b1b8-b751da5a7101");
          database.insertRequest(id, user);
          DeletionLease stale =
              database
                  .repository
                  .claimAvailable("old-worker", NOW, Duration.ofSeconds(30), 1)
                  .getFirst();
          assertThat(database.repository.startStep(stale, DeletionStep.SESSIONS_REVOKED, NOW))
              .isTrue();
          database.jdbc.update(
              "update public.account_deletion_requests set lease_expires_at=clock_timestamp()-interval '1 ms' where id=?",
              id);
          DeletionLease current =
              database
                  .repository
                  .claimAvailable("new-worker", NOW.plusSeconds(31), Duration.ofSeconds(30), 1)
                  .getFirst();

          assertThat(database.repository.completeStep(stale, DeletionStep.SESSIONS_REVOKED, NOW))
              .isFalse();
          assertThat(database.repository.retry(stale, "STALE", NOW.plusSeconds(1), NOW)).isFalse();
          assertThat(database.repository.completeAuthDeletionAndClearSubject(stale, NOW)).isFalse();
          assertThat(current.fencingToken()).isEqualTo(stale.fencingToken() + 1);
          assertThat(
                  database.jdbc.queryForObject(
                      "select completed_at is null from public.account_deletion_steps where request_id=? and attempt=1",
                      Boolean.class,
                      id))
              .isTrue();

          Instant currentAt = Instant.now();
          assertThat(
                  database.repository.startStep(current, DeletionStep.AUTH_USER_DELETED, currentAt))
              .isTrue();
          assertThat(database.repository.completeAuthDeletionAndClearSubject(current, currentAt))
              .isTrue();
          assertThat(database.repository.succeed(current, currentAt)).isTrue();
          assertThat(
                  database.jdbc.queryForObject(
                      "select auth_subject_ciphertext is null and status='succeeded' from public.account_deletion_requests where id=?",
                      Boolean.class,
                      id))
              .isTrue();
        });
  }

  @Test
  void PostgreSQL16과17에서_cancel과_첫_Storage_marker는_경쟁해_하나만_승리한다() {
    forEachDatabase(
        database -> {
          String id = "01K4V106000000000000000102";
          UUID user = UUID.fromString("46d9a0ca-3472-4f7e-b1b8-b751da5a7102");
          database.insertRequest(id, user);
          DeletionLease lease =
              database
                  .repository
                  .claimAvailable("race-worker", Instant.now(), Duration.ofSeconds(30), 1)
                  .getFirst();
          CountDownLatch start = new CountDownLatch(1);
          try (var pool = Executors.newFixedThreadPool(2)) {
            var marker =
                pool.submit(
                    () -> {
                      start.await(5, TimeUnit.SECONDS);
                      return database.repository.startStep(
                          lease, DeletionStep.PROFILE_IMAGES_DELETED, Instant.now());
                    });
            var cancel =
                pool.submit(
                    () -> {
                      start.await(5, TimeUnit.SECONDS);
                      return database.jdbc.update(
                              "update public.account_deletion_requests set cancellation_requested=true where id=? and cancellation_requested=false and destructive_started_at is null",
                              id)
                          == 1;
                    });
            start.countDown();
            assertThat(List.of(await(marker), await(cancel)))
                .containsExactlyInAnyOrder(true, false);
          }
          assertThat(
                  database.jdbc.queryForObject(
                      "select (destructive_started_at is not null)::int + cancellation_requested::int from public.account_deletion_requests where id=?",
                      Integer.class,
                      id))
              .isOne();
        });
  }

  @Test
  void profile_FK_NULL_재시작도_subject의_idempotency_response와_outbox_path를_남기지_않는다() {
    forEachDatabase(
        database -> {
          String id = "01K4V106000000000000000103";
          UUID user = UUID.fromString("46d9a0ca-3472-4f7e-b1b8-b751da5a7103");
          database.insertRequest(id, user);
          database.jdbc.update(
              "insert into public.api_idempotency_records(owner_sub,http_method,normalized_path,idempotency_key,request_hash,attempt_token,state,response_status,response_headers,response_body,created_at,completed_at,expires_at) values (?,'DELETE','/api/v1/me',?,repeat('a',64),?,'COMPLETED',202,?::bytea,?::bytea,statement_timestamp(),statement_timestamp(),statement_timestamp()+interval '24 hours')",
              user,
              UUID.randomUUID(),
              UUID.randomUUID(),
              "headers".getBytes(),
              ("{\"subject\":\"" + user + "\"}").getBytes());
          database.jdbc.update(
              "insert into public.profile_image_cleanup_outbox(owner_user_id,object_key,storage_etag,source_profile_version,reason) values (?,?,?,1,'account_deletion')",
              user,
              user + "/profile/00000000-0000-0000-0000-000000000103",
              "\"etag-103\"");
          database.jdbc.update("delete from public.user_profiles where id=?", user);
          assertThat(
                  database.jdbc.queryForObject(
                      "select user_profile_id is null from public.account_deletion_requests where id=?",
                      Boolean.class,
                      id))
              .isTrue();
          DeletionLease lease =
              database
                  .repository
                  .claimAvailable("erase-worker", Instant.now(), Duration.ofSeconds(30), 1)
                  .getFirst();

          database.erasure.deleteAndAnonymize(lease, AuthSubject.of(user.toString()));

          assertThat(
                  database.jdbc.queryForObject(
                      "select count(*) from public.api_idempotency_records where owner_sub=?",
                      Integer.class,
                      user))
              .isZero();
          assertThat(
                  database.jdbc.queryForObject(
                      "select count(*) from public.profile_image_cleanup_outbox where owner_user_id=? or object_key like ?",
                      Integer.class,
                      user,
                      user + "/%"))
              .isZero();
        });
  }

  private static void forEachDatabase(java.util.function.Consumer<Database> assertion) {
    for (String image : IMAGES) {
      PostgreSQLContainer container = PostgreSqlTestContainerFactory.create(image);
      try {
        container.start();
        DriverManagerDataSource dataSource =
            new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        assertion.accept(new Database(dataSource));
      } finally {
        container.stop();
      }
    }
  }

  private static boolean await(Future<Boolean> result) {
    try {
      return result.get(10, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError("concurrent account deletion operation did not finish", failure);
    }
  }

  private static final class Database {
    private final JdbcTemplate jdbc;
    private final JdbcAccountDeletionWorkRepository repository;
    private final JdbcAppOwnedDataErasure erasure;

    private Database(DriverManagerDataSource dataSource) {
      jdbc = new JdbcTemplate(dataSource);
      NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
      TransactionTemplate transaction =
          new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      repository = new JdbcAccountDeletionWorkRepository(named, transaction);
      erasure = new JdbcAppOwnedDataErasure(named, transaction);
    }

    private void insertRequest(String id, UUID user) {
      jdbc.update("insert into auth.users(id,email) values (?,?)", user, user + "@issue106.test");
      jdbc.update(
          "insert into public.user_profiles(id,email) values (?,?)", user, user + "@issue106.test");
      jdbc.update(
          """
          insert into public.account_deletion_requests(
            id,user_profile_id,idempotency_hash,request_hash,status_token_hash,
            auth_subject_fingerprint,
            status_token_ciphertext,status_token_key_version,status_token_expires_at,
            auth_subject_ciphertext,auth_subject_key_version,status,requested_at)
          values (?, ?, decode(repeat('11',32),'hex'), decode(repeat('22',32),'hex'),
            decode(repeat('33',32),'hex'), decode(repeat('44',32),'hex'),
            'token-cipher', 'key-v1',
            ?::timestamptz, 'subject-cipher', 'key-v1', 'queued', ?::timestamptz)
          """,
          id,
          user,
          OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
          OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }
  }
}
