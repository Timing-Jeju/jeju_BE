package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionLease;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionStep;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.JdbcAccountDeletionWorkRepository;
import com.timingjeju.api.domain.accountdeletion.worker.adapter.JdbcAppOwnedDataErasure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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

  @Test
  void PostgreSQL16과17에서_reclaim은_stale_complete_retry_authClear를_모두_rollback한다() {
    forEachDatabase(
        database -> {
          database.jdbc.execute("set time zone 'Asia/Seoul'");
          database.assertReclaimWins(
              "01K4V106000000000000000111",
              UUID.fromString("46d9a0ca-3472-4f7e-b1b8-b751da5a7111"),
              Mutation.COMPLETE);
          database.assertReclaimWins(
              "01K4V106000000000000000112",
              UUID.fromString("46d9a0ca-3472-4f7e-b1b8-b751da5a7112"),
              Mutation.RETRY);
          database.assertReclaimWins(
              "01K4V106000000000000000113",
              UUID.fromString("46d9a0ca-3472-4f7e-b1b8-b751da5a7113"),
              Mutation.AUTH_CLEAR);
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
                  .claimAvailable("race-worker", database.now(), Duration.ofSeconds(30), 1)
                  .getFirst();
          CountDownLatch start = new CountDownLatch(1);
          try (var pool = Executors.newFixedThreadPool(2)) {
            var marker =
                pool.submit(
                    () -> {
                      start.await(5, TimeUnit.SECONDS);
                      return database.repository.startStep(
                          lease, DeletionStep.PROFILE_IMAGES_DELETED, database.now());
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
                  .claimAvailable("erase-worker", database.now(), Duration.ofSeconds(30), 1)
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

  private static <T> T await(Future<T> result) {
    try {
      return result.get(10, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError("concurrent account deletion operation did not finish", failure);
    }
  }

  private static final class Database {
    private final DriverManagerDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final JdbcAccountDeletionWorkRepository repository;
    private final JdbcAppOwnedDataErasure erasure;

    private Database(DriverManagerDataSource dataSource) {
      this.dataSource = dataSource;
      jdbc = new JdbcTemplate(dataSource);
      NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
      TransactionTemplate transaction =
          new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      repository = new JdbcAccountDeletionWorkRepository(named, transaction);
      erasure = new JdbcAppOwnedDataErasure(named, transaction);
      jdbc.execute(
          """
          create or replace function public.test_pause_account_deletion_reclaim()
          returns trigger language plpgsql as $$
          begin
            if old.lease_owner like 'old-%' and new.lease_owner like 'current-%' then
              perform pg_advisory_xact_lock(106065);
            end if;
            return new;
          end
          $$
          """);
      jdbc.execute(
          """
          create trigger test_pause_account_deletion_reclaim
          before update on public.account_deletion_requests
          for each row execute function public.test_pause_account_deletion_reclaim()
          """);
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
          values (?, ?, digest(? || '-idem','sha256'), digest(? || '-request','sha256'),
            digest(? || '-status','sha256'), digest(? || '-subject','sha256'),
            'token-cipher', 'key-v1',
            clock_timestamp()+interval '1 hour', 'subject-cipher', 'key-v1',
            'queued', clock_timestamp())
          """,
          id,
          user,
          id,
          id,
          id,
          id);
    }

    private Instant now() {
      return jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    private void assertReclaimWins(String id, UUID user, Mutation mutation) {
      insertRequest(id, user);
      Instant claimedAt = now();
      DeletionLease stale =
          repository
              .claimAvailable("old-" + mutation, claimedAt, Duration.ofSeconds(30), 1)
              .getFirst();
      DeletionStep step =
          mutation == Mutation.AUTH_CLEAR
              ? DeletionStep.AUTH_USER_DELETED
              : DeletionStep.SESSIONS_REVOKED;
      assertThat(repository.startStep(stale, step, now())).isTrue();
      Instant explicitExpiry =
          jdbc.queryForObject(
                  "update public.account_deletion_requests set lease_expires_at=clock_timestamp()+interval '30 seconds' where id=? returning lease_expires_at",
                  OffsetDateTime.class,
                  id)
              .toInstant();

      try (Connection blocker = dataSource.getConnection();
          PreparedStatement lock = blocker.prepareStatement("select pg_advisory_lock(106065)");
          var pool = Executors.newFixedThreadPool(2)) {
        lock.executeQuery().close();
        CountDownLatch reclaimEntered = new CountDownLatch(1);
        Future<DeletionLease> reclaim =
            pool.submit(
                () -> {
                  reclaimEntered.countDown();
                  return repository
                      .claimAvailable(
                          "current-" + mutation,
                          explicitExpiry.plusMillis(1),
                          Duration.ofSeconds(30),
                          1)
                      .getFirst();
                });
        assertThat(reclaimEntered.await(2, TimeUnit.SECONDS)).isTrue();
        boolean reclaimWaiting = awaitLockWaiters(1);
        CountDownLatch staleEntered = new CountDownLatch(1);
        Future<Boolean> staleMutation =
            pool.submit(
                () -> {
                  staleEntered.countDown();
                  Instant at = now();
                  return switch (mutation) {
                    case COMPLETE -> repository.completeStep(stale, step, at);
                    case RETRY -> repository.retry(stale, "STALE_RETRY", at.plusSeconds(1), at);
                    case AUTH_CLEAR -> repository.completeAuthDeletionAndClearSubject(stale, at);
                  };
                });
        assertThat(staleEntered.await(2, TimeUnit.SECONDS)).isTrue();
        boolean staleWaitingBehindReclaim = awaitLockWaiters(2);

        try (PreparedStatement unlock =
            blocker.prepareStatement("select pg_advisory_unlock(106065)")) {
          unlock.executeQuery().close();
        }
        assertThat(reclaimWaiting).isTrue();
        assertThat(staleWaitingBehindReclaim).isTrue();
        DeletionLease current = await(reclaim);
        assertThat(await(staleMutation)).isFalse();
        assertThat(current.fencingToken()).isEqualTo(stale.fencingToken() + 1);
        assertThat(
                jdbc.queryForObject(
                    "select fencing_token=? and lease_owner=? from public.account_deletion_requests where id=?",
                    Boolean.class,
                    current.fencingToken(),
                    current.owner(),
                    id))
            .isTrue();
        assertThat(
                jdbc.queryForObject(
                    "select completed_at is null and failure_code is null from public.account_deletion_steps where request_id=? and step=? and attempt=?",
                    Boolean.class,
                    id,
                    step.name(),
                    stale.attempt()))
            .isTrue();

        assertThat(repository.startStep(current, step, now())).isTrue();
        Instant currentAt = now();
        switch (mutation) {
          case COMPLETE -> {
            assertThat(repository.completeStep(current, step, currentAt)).isTrue();
            assertThat(stepCompleted(id, step, current.attempt())).isTrue();
          }
          case RETRY -> {
            assertThat(
                    repository.retry(current, "CURRENT_RETRY", currentAt.plusSeconds(1), currentAt))
                .isTrue();
            assertThat(
                    jdbc.queryForObject(
                        "select failure_code='CURRENT_RETRY' from public.account_deletion_steps where request_id=? and step=? and attempt=?",
                        Boolean.class,
                        id,
                        step.name(),
                        current.attempt()))
                .isTrue();
            jdbc.update(
                "update public.account_deletion_requests set status='failed', completed_at=clock_timestamp(), next_retry_at=null where id=?",
                id);
          }
          case AUTH_CLEAR -> {
            assertThat(repository.completeAuthDeletionAndClearSubject(current, currentAt)).isTrue();
            assertThat(stepCompleted(id, step, current.attempt())).isTrue();
            assertThat(
                    jdbc.queryForObject(
                        "select auth_subject_ciphertext is null and auth_subject_key_version is null from public.account_deletion_requests where id=?",
                        Boolean.class,
                        id))
                .isTrue();
          }
        }
      } catch (Exception failure) {
        throw new AssertionError("two-session reclaim race failed for " + mutation, failure);
      }
    }

    private boolean awaitLockWaiters(int expected) throws InterruptedException {
      for (int attempt = 0; attempt < 200; attempt++) {
        Integer waiting =
            jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'",
                Integer.class);
        if (waiting != null && waiting >= expected) {
          return true;
        }
        Thread.sleep(10);
      }
      return false;
    }

    private boolean stepCompleted(String id, DeletionStep step, int attempt) {
      return Boolean.TRUE.equals(
          jdbc.queryForObject(
              "select completed_at is not null from public.account_deletion_steps where request_id=? and step=? and attempt=?",
              Boolean.class,
              id,
              step.name(),
              attempt));
    }
  }

  private enum Mutation {
    COMPLETE,
    RETRY,
    AUTH_CLEAR
  }
}
